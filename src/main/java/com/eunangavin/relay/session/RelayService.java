package com.eunangavin.relay.session;

import com.eunangavin.relay.Log;
import com.eunangavin.relay.config.RelayConfig;
import com.eunangavin.relay.protocol.ErrorCode;
import com.eunangavin.relay.protocol.Frame;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Frame dispatch — the relay's behaviour, with no knowledge of sockets or threads.
 *
 * <p>Everything here runs on the calling connection's reader thread. That is deliberate:
 * there is no shared worker pool and no shared dispatcher, because a shared pool is exactly
 * how one slow client starves everyone else. Replies go out through
 * {@link ClientConnection#offer}, which never blocks.
 *
 * <p>For a {@code SEND} this means the <em>sender's</em> thread runs the recipient's
 * delivery pump. That is safe for one reason only: {@code offer} puts a frame on the
 * recipient's bounded queue and returns, so the sender's thread never touches the
 * recipient's socket however badly the recipient is behaving.
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
     * <p>The switch is <b>exhaustive with no {@code default} branch</b> — {@link Frame} is
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

            // Server-to-client frames arriving inbound mean the peer is not speaking our
            // protocol. The frame decoded, but in this direction it is nonsense, so we say
            // so and close rather than guess at intent.
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

    private void handleRegister(ClientConnection connection, Frame.Register register) {
        if (connection.session() != null) {
            // One identity per connection. Allowing a second would leave the first session
            // bound to a connection that no longer considers itself to be that client.
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

        // attach() has already requeued anything the previous connection held unacknowledged,
        // so this count is the real backlog that is about to arrive.
        ClientSession session = result.session();
        sendOrDrop(connection, new Frame.Registered(register.clientId(), session.pendingCount()));
        Log.info("%s registered as '%s' (%d pending, %d identities known)",
                connection.label(), register.clientId(), session.pendingCount(), registry.size());

        pump(session);
    }

    // ------------------------------------------------------------------ send and ack

    private void handleSend(ClientConnection connection, Frame.Send send) {
        ClientSession sender = connection.session();
        if (sender == null) {
            sendOrDrop(connection, new Frame.Error(ErrorCode.NOT_REGISTERED, "register before sending"));
            return;
        }

        // A SEND without a messageId cannot even be REJECTED, because a Rejected frame is
        // keyed by that id - there is literally nothing to answer. So the peer is not
        // speaking our protocol, and it gets the same treatment as any malformed frame.
        if (isBlank(send.messageId()) || isBlank(send.to()) || send.payload() == null) {
            rejectUnusableFrame(connection, "SEND requires messageId, to and payload");
            return;
        }

        // Bytes, not String.length(). A multi-byte character makes those differ, and the
        // bound is a byte bound. The connection survives: the frame parsed fine, we are
        // simply declining its contents - unlike a frame-size breach, which desynchronises
        // the stream and therefore has to close.
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
                // ACCEPTED means "in the recipient's mailbox" - NOT delivered, not read. It
                // is answered before the pump runs, so the sender's confirmation never
                // depends on the recipient being reachable.
                sendOrDrop(connection, new Frame.Accepted(send.messageId()));
                Log.info("%s -> %s accepted %s (%s now holds %d)",
                        sender.clientId(), send.to(), send.messageId(),
                        send.to(), target.mailboxSize());
                pump(target);
            }
        }
    }

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

        // Scoped to this client's OWN mailbox. A client acking someone else's message id
        // never holds a reference to that mailbox, so removing it is structurally impossible
        // - not a validation that could be dropped in a refactor.
        //
        // The result is deliberately not turned into an error. A stale or repeated ack is a
        // no-op that still answers ACK_OK, because at-least-once delivery GUARANTEES double
        // acks: ack, connection drops before it lands, reconnect, redelivered, ack again.
        // Erroring would punish a client for behaviour our own guarantee forces on it.
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
     * <p>The policy lives here rather than in {@code Connection} because it is a relay
     * decision, not a transport one. Three options existed: block the calling thread
     * (reintroduces the very problem the bounded queue solves — and the caller may be
     * another client's reader thread), drop the frame silently (loses a message we may
     * already have called ACCEPTED), or drop the connection.
     *
     * <p>We drop the connection. A client this far behind is not keeping up, and its session
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
