package com.eunangavin.relay.session;

import com.eunangavin.relay.Log;
import com.eunangavin.relay.config.RelayConfig;
import com.eunangavin.relay.protocol.ErrorCode;
import com.eunangavin.relay.protocol.Frame;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The only class that knows a message can move from one client to another.
 *
 * Everything else in this codebase is support for that. {@code Frame} and {@code FrameCodec}
 * turn a message into bytes, {@code Connection} moves the bytes, {@code Mailbox} stores it.
 * This class is what decides it goes from Alice to Bob at all. Delete it and the server
 * still accepts connections, still decodes frames, still has mailboxes and nothing
 * happens. It is a working TCP server that is not a relay.
 *
 * It is also the only place where two clients meet. Every other class knows about one
 * connection, or one identity, or one frame.
 *
 * Three verbs
 *
 * REGISTER — turns an anonymous socket into a known identity.
 * SEND — puts a message in someone else's mailbox. This is the relay.*
 * ACK — removes a message. Nothing else does.
 *
 *   REGISTER  ->  REGISTERED | ERROR
 *   SEND      ->  ACCEPTED   | REJECTED
 *   ACK       ->  ACK_OK
 *
 * A server-to-client frame arriving inbound is not the protocol in that direction, so it is
 * answered and the connection closed.
 *
 * There is one entry point, {@link #onFrame}, called by a {@code net.Connection}'s reader
 * thread once it has a decoded frame. That call is the whole boundary between transport and
 * behaviour. This file never imports {@code java.net}: replies leave through
 * {@link ClientConnection#offer}, an interface this package defines and {@code net}
 * implements, which is what lets everything below here be tested against a fake with no
 * sockets and no timing.
 *
 * Threading
 * Everything runs on the reader thread of the connection the frame arrived on. There is no
 * worker pool and no shared dispatcher, because a shared pool is exactly how one slow client
 * starves everyone else.
 *
 * For a SEND this means the sender's thread runs the recipient's delivery pump, while
 * holding the recipient's lock. That is safe for one reason only: {@code offer} never
 * blocks. It hands a frame to the recipient's bounded queue and returns, so the sender's
 * thread stops at that queue and never touches the recipient's socket, however badly the
 * recipient is behaving.
 */
public final class RelayService {

    private final ClientRegistry registry;
    private final RelayConfig config;
    private volatile boolean shuttingDown;

    public RelayService(ClientRegistry registry, RelayConfig config) {
        this.registry = registry;
        this.config = config;
    }

    public ClientRegistry registry() {
        return registry;
    }

    public void shuttingDown() {
        this.shuttingDown = true;
    }

    /**
     * Handles one inbound frame.
     *
     * The switch is exhaustive with no {@code default} branch — {@link Frame} is
     * sealed, so the compiler knows every case. Adding a new frame type turns every
     * unhandled site into a compile error rather than a runtime surprise.
     */
    public void onFrame(ClientConnection connection, Frame frame) {
        if (shuttingDown) {
            sendOrDrop(connection, new Frame.Error(ErrorCode.SERVER_SHUTTING_DOWN, "server is stopping"));
            return;
        }

        switch (frame) {
            case Frame.Register register -> handleRegister(connection, register);
            case Frame.Send send -> handleSend(connection, send);
            case Frame.Ack ack -> handleAck(connection, ack);

            // Server-to-client frames arriving inbound: decoded fine, but nonsense in this
            // direction. Say so and close rather than guess at intent.
            case Frame.Registered ignored -> rejectInboundServerFrame(connection, frame);
            case Frame.Accepted ignored -> rejectInboundServerFrame(connection, frame);
            case Frame.Rejected ignored -> rejectInboundServerFrame(connection, frame);
            case Frame.AckOk ignored -> rejectInboundServerFrame(connection, frame);
            case Frame.Deliver ignored -> rejectInboundServerFrame(connection, frame);
            case Frame.Error ignored -> rejectInboundServerFrame(connection, frame);
            case Frame.Shutdown ignored -> rejectInboundServerFrame(connection, frame);
        }
    }

    // ------------------------------------------------------------------ register

    /**
     * {@code REGISTER} → {@code REGISTERED}, or {@code ERROR}.
     *
     * Claims an identity for this connection. If the name is new a session is created; if
     * it already exists the connection reattaches to the same session, mailbox intact
     * which is requirement 6, and the reason nothing needs to be restored on reconnect.
     *
     * If another connection currently holds the name it is evicted, told why, and
     * closed here rather than inside the session lock. Takeover rather than rejection,
     * because a half-open TCP connection is undetectable until a write to it fails, so
     * refusing would strand a client whose network dropped.
     *
     * The {@code pending} count in the reply is read after attach, which has
     * already requeued anything the previous connection left unacknowledged — so it is the
     * true backlog, not a stale figure. The final {@link #pump} is what delivers it.
     */
    private void handleRegister(ClientConnection connection, Frame.Register register) {
        if (connection.session() != null) {
            // One identity per connection, or the first session is left bound to a
            // connection that no longer considers itself that client.
            sendOrDrop(connection, new Frame.Error(ErrorCode.ALREADY_REGISTERED,
                    "this connection is already registered as " + connection.session().clientId()));
            return;
        }

        if (!ClientRegistry.isValidClientId(register.clientId())) {
            sendOrDrop(connection, new Frame.Error(ErrorCode.NOT_REGISTERED,
                    "clientId must be 1-" + ClientRegistry.MAX_CLIENT_ID_LENGTH
                            + " characters of [A-Za-z0-9._-]"));
            return;
        }

        ClientRegistry.Registration result = registry.register(register.clientId(), connection);

        // Closed outside any lock - see ClientSession.attach.
        if (result.evicted() != null) {
            Log.warn("%s took over identity '%s' from %s",
                    connection.label(), register.clientId(), result.evicted().label());
            sendOrDrop(result.evicted(), new Frame.Error(ErrorCode.ALREADY_REGISTERED,
                    "identity claimed by another connection"));
            result.evicted().close("identity taken over");
        }

        // attach() has already requeued anything the previous connection held unacked, so
        // this count is the real backlog about to arrive.
        ClientSession session = result.session();
        sendOrDrop(connection, new Frame.Registered(register.clientId(), session.pendingCount()));
        Log.info("%s registered as '%s' (%d pending, %d identities known)",
                connection.label(), register.clientId(), session.pendingCount(), registry.size());

        pump(session);
    }

    // ------------------------------------------------------------------ send and ack

    /**
     * {@code SEND} → {@code ACCEPTED} or {@code REJECTED}.
     *
     * Validates, resolves the recipient, and enqueues into their mailbox. Runs
     * entirely on the sender's reader thread, including the recipient's delivery
     * pump
     * Safe only because {@link ClientConnection#offer} never blocks, so this thread
     * stops at the recipient's queue and never touches their socket.
     *
     * Two different rejection shapes, and the difference is whether the peer is still
     * speaking the protocol:
     *
     *   {@code REJECTED} — keyed by {@code messageId}, connection survives:
     *       unknown recipient, duplicate id, payload too large, mailbox full.
     *   {@code ERROR} then close — a SEND missing {@code messageId} cannot even
     *       be rejected, because a Rejected frame is keyed by that id. There is nothing to
     *       answer, so it is treated as a malformed frame.
     *
     *
     * @code ACCEPTED} is answered before the pump runs and means only "in the
     * recipient's mailbox" — not delivered, not read. That ordering is what stops the
     * sender's confirmation depending on whether the recipient is reachable.
     *
     * Only the recipient's lock is ever taken. Locking sender and recipient
     * together would deadlock the instant two clients sent to each other at once.
     */
    private void handleSend(ClientConnection connection, Frame.Send send) {
        ClientSession sender = connection.session();
        if (sender == null) {
            sendOrDrop(connection, new Frame.Error(ErrorCode.NOT_REGISTERED, "register before sending"));
            return;
        }

        // A SEND without a messageId cannot even be REJECTED — a Rejected frame is keyed by
        // that id, so there is nothing to answer. Treated as any other malformed frame.
        if (isBlank(send.messageId()) || isBlank(send.to()) || send.payload() == null) {
            rejectUnusableFrame(connection, "SEND requires messageId, to and payload");
            return;
        }

        // Bytes, not String.length() — a multi-byte character makes those differ. The
        // connection survives: the frame parsed fine, we are declining its contents. A
        // frame-size breach is the opposite, because it desynchronises the stream.
        int payloadBytes = send.payload().getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > config.maxPayloadBytes()) {
            reject(connection, send, ErrorCode.PAYLOAD_TOO_LARGE,
                    payloadBytes + " bytes exceeds limit " + config.maxPayloadBytes());
            return;
        }

        Optional<ClientSession> recipient = registry.find(send.to());
        if (recipient.isEmpty()) {
            reject(connection, send, ErrorCode.UNKNOWN_RECIPIENT,
                    "no client registered as " + send.to());
            return;
        }

        ClientSession target = recipient.get();
        Message message = new Message(send.messageId(), sender.clientId(), send.payload());

        // Only the RECIPIENT's lock is taken, never both. Locking sender and recipient
        // together would deadlock the moment two clients sent to each other at once.
        Mailbox.EnqueueResult outcome = target.enqueue(message);

        switch (outcome) {
            case DUPLICATE_ID -> reject(connection, send, ErrorCode.DUPLICATE_MESSAGE_ID,
                    "id " + send.messageId() + " is already live for " + send.to());
            case MAILBOX_FULL -> reject(connection, send, ErrorCode.MAILBOX_FULL,
                    send.to() + " is at its mailbox limit of " + config.maxMailboxMessages());
            case ACCEPTED -> {
                // ACCEPTED means "in the recipient's mailbox" — not delivered, not read.
                // Answered before the pump runs, so the sender's confirmation never depends
                // on the recipient being reachable.
                sendOrDrop(connection, new Frame.Accepted(send.messageId()));
                Log.info("%s -> %s accepted %s (%s now holds %d)",
                        sender.clientId(), send.to(), send.messageId(),
                        send.to(), target.mailboxSize());
                pump(target);
            }
        }
    }

    /**
     * {@code ACK} -> {@code ACK_OK}, always.
     *
     * An acknowledgement is the only thing that removes a message. The lookup is scoped
     * to the acking client's own mailbox, which makes "removed only after the correct
     * recipient acknowledges it" structurally true: a client acking another client's id
     * never holds a reference to that mailbox, so it cannot remove anything.
     * That is stronger than an ownership check, which would leave a {@code remove(id)} on shared
     * state one refactor away from a serious bug.
     *
     * ACK_OK is returned whether or not anything was removed. At-least-once delivery
     * can produce duplicate acks: a client acks, the connection drops before it lands,
     * the client reconnects, the message is redelivered, and it acks again. Erroring
     * would punish the client for behaviour our own delivery guarantee forces on it.
     *
     * The cost is that an ack for the wrong recipient looks the same as a stale one.
     * Both are harmless no-ops.
     */
    private void handleAck(ClientConnection connection, Frame.Ack ack) {
        ClientSession session = connection.session();
        if (session == null) {
            sendOrDrop(connection, new Frame.Error(ErrorCode.NOT_REGISTERED, "register before acking"));
            return;
        }
        if (isBlank(ack.messageId())) {
            rejectUnusableFrame(connection, "ACK requires a messageId");
            return;
        }

        // Scoped to this client's OWN mailbox, so acking someone else's id is structurally
        // impossible rather than a validation that could be dropped in a refactor. The
        // result is deliberately not an error: at-least-once GUARANTEES double acks.
        if (session.ack(ack.messageId())) {
            Log.info("%s acked %s (%d remaining)",
                    session.clientId(), ack.messageId(), session.mailboxSize());
        }
        sendOrDrop(connection, new Frame.AckOk(ack.messageId()));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Delivers a session's backlog, then closes any connection that could not keep up.
     *
     * <p>The close happens <b>here, outside the session lock</b>. {@code close} is graceful
     * and waits for the writer to drain; holding the lock across that wait would let one
     * slow socket stall every send to that identity.
     */
    private void pump(ClientSession session) {
        ClientConnection slow = session.pump();
        if (slow != null) {
            Log.warn("%s could not keep up; dropping connection (%s)",
                    slow.label(), ErrorCode.SLOW_CONSUMER);
            slow.close("outbound queue full: " + ErrorCode.SLOW_CONSUMER);
        }
    }

    private void reject(ClientConnection connection, Frame.Send send, ErrorCode code, String reason) {
        Log.info("%s rejected %s: %s", connection.label(), send.messageId(), code);
        sendOrDrop(connection, new Frame.Rejected(send.messageId(), code, reason));
    }

    private void rejectUnusableFrame(ClientConnection connection, String reason) {
        sendOrDrop(connection, new Frame.Error(ErrorCode.MALFORMED_FRAME, reason));
        connection.close(reason);
    }

    private void rejectInboundServerFrame(ClientConnection connection, Frame frame) {
        sendOrDrop(connection, new Frame.Error(ErrorCode.MALFORMED_FRAME,
                frame.getClass().getSimpleName() + " is a server-to-client frame"));
        connection.close("client sent a server frame");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Sends a frame, applying the slow-consumer policy when the queue is full.
     *
     * The policy lives here rather than in {@code Connection} because it is a relay
     * decision, not a transport one. Three options existed: block the calling thread
     * (reintroduces the very problem the bounded queue solves — and the caller may be
     * another client's reader thread), drop the frame silently (loses a message we may
     * already have called ACCEPTED), or drop the connection.
     *
     * We drop the connection. A client this far behind is not keeping up, and its session
     * outlives the socket, so reconnecting recovers everything it missed.
     */
    private void sendOrDrop(ClientConnection connection, Frame frame) {
        if (!connection.offer(frame)) {
            Log.warn("%s outbound queue full; dropping connection (%s)",
                    connection.label(), ErrorCode.SLOW_CONSUMER);
            connection.close("outbound queue full: " + ErrorCode.SLOW_CONSUMER);
        }
    }

    /** Called when a connection's reader loop ends, for any reason. */
    public void onDisconnect(ClientConnection connection) {
        ClientSession session = connection.session();
        // detach() requeues anything this connection held unacknowledged, so it is available
        // again on reconnect - requirement 7.
        registry.disconnect(connection);
        if (session != null) {
            Log.info("%s disconnected; session '%s' retained with %d message(s)%s",
                    connection.label(), session.clientId(), session.mailboxSize(),
                    session.isOnline() ? " (already reconnected elsewhere)" : " and is now offline");
        }
    }
}
