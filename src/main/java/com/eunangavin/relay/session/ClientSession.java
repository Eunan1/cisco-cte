package com.eunangavin.relay.session;

import com.eunangavin.relay.protocol.Frame;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A logical client. <b>Identity outlives the connection.</b>
 *
 * <p>This is the single most important type in the project. A {@code ClientSession} is
 * created on first registration and then persists: the socket is a <em>nullable field on
 * it</em>, not the other way round. Disconnecting sets that field to null; reconnecting
 * sets it again. Nothing else is lost.
 *
 * <p>In STORY-3 the mailbox hangs off this object, and requirements 5, 6 and 7 of the brief
 * — retention while offline, reattachment by name, redelivery of unacked messages — all
 * fall out of that placement. Had the mailbox hung off the connection instead, every one of
 * them would have been a rewrite.
 *
 * <p><b>Locking.</b> A {@link ReentrantLock} rather than {@code synchronized}: on Java 21 a
 * virtual thread that blocks inside a synchronized block pins its carrier platform thread,
 * and enough pinned carriers starve the scheduler. (JEP 491 removed that limitation in Java
 * 24, but this artifact targets 21 and may run on it. {@code ReentrantLock} also wins
 * independently for {@code tryLock} and interruptibility.)
 */
public final class ClientSession {

    private final String clientId;
    private final ReentrantLock lock = new ReentrantLock();

    /** Null when offline. Guarded by {@link #lock}. */
    private ClientConnection connection;

    /** This identity's messages. Guarded by {@link #lock} - Mailbox is not thread-safe. */
    private final Mailbox mailbox;

    ClientSession(String clientId, int maxMailboxMessages) {
        this.clientId = clientId;
        this.mailbox = new Mailbox(maxMailboxMessages);
    }

    public String clientId() {
        return clientId;
    }

    /**
     * Binds a connection to this identity, evicting any current one.
     *
     * <p><b>Takeover, not rejection.</b> If a connection already holds this name we replace
     * it rather than refusing the newcomer. The reason is that a half-open TCP connection is
     * undetectable until a write to it fails — so rejecting would permanently strand a
     * client whose network dropped, with the server insisting it is still connected. The
     * trade-off, documented in APPROACH.md: two clients genuinely sharing a name will fight
     * over it.
     *
     * @return the evicted connection, or null if none. <b>The caller must close it outside
     *         the lock</b> — closing does socket I/O, and holding a lock across I/O is how
     *         one slow socket becomes everyone's problem.
     */
    ClientConnection attach(ClientConnection incoming) {
        lock.lock();
        try {
            ClientConnection evicted = this.connection;
            // Anything delivered to the connection being replaced was never acknowledged,
            // so it goes back to pending for the newcomer. Without this, a takeover would
            // strand those messages in inflight with nobody left to ack them.
            mailbox.requeueInflight();
            this.connection = incoming;
            return evicted == incoming ? null : evicted;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Detaches a connection, if it is still the current one.
     *
     * <p>The identity check is load-bearing. Consider:
     *
     * <pre>
     *   t0  connection A registers as "alice"       connection = A
     *   t1  connection B registers as "alice"       connection = B, A evicted
     *   t2  A's reader loop exits and detaches      ← must NOT null out B
     * </pre>
     *
     * Without the check, a slow disconnect from an evicted connection silently kills the
     * healthy connection that replaced it moments earlier. The bug is invisible to any test
     * that does not reconnect quickly.
     */
    void detach(ClientConnection leaving) {
        lock.lock();
        try {
            if (this.connection == leaving) {
                // Requeue BEFORE clearing the connection, and only for the connection that
                // actually held the identity. Requirement 7: a message delivered before a
                // disconnect but not acknowledged must be available again on reconnect.
                mailbox.requeueInflight();
                this.connection = null;
            }
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ mailbox

    /** Queues a message for this identity. Bounds and duplicate detection live in Mailbox. */
    Mailbox.EnqueueResult enqueue(Message message) {
        lock.lock();
        try {
            return mailbox.offer(message);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Delivers everything pending to the attached connection, if there is one.
     *
     * <p>Runs on the <b>caller's</b> thread — for a SEND that is the <em>sender's</em>
     * reader thread — while holding the <b>recipient's</b> lock. That is only safe because
     * {@link ClientConnection#offer} never blocks: it puts a frame on the recipient's
     * bounded queue and returns, and the recipient's own writer thread does the socket
     * write. The sender's thread therefore never touches the recipient's socket, however
     * badly the recipient is behaving.
     *
     * <p>Delivery is <b>pipelined</b>: everything available goes out rather than waiting for
     * each ack. Stop-and-wait would cost a round trip per message and buy nothing, because
     * the bounded queue already provides flow control. The consequence is that several
     * messages are inflight at once and acks may arrive out of order — which is why inflight
     * is a map keyed by id rather than a queue.
     *
     * @return a connection the <b>caller must close</b> because its outbound queue is full,
     *         or null. Closing is deliberately not done here: {@code close} is graceful and
     *         waits for the writer to drain, and holding this lock across that wait would
     *         let one slow socket stall every send to this identity.
     */
    ClientConnection pump() {
        lock.lock();
        try {
            ClientConnection target = this.connection;
            if (target == null) {
                return null;   // offline: messages simply wait
            }
            Message message;
            while ((message = mailbox.takeForDelivery()) != null) {
                if (!target.offer(new Frame.Deliver(message.id(), message.from(), message.payload()))) {
                    mailbox.returnToFront(message);   // never actually delivered
                    return target;                    // caller closes it, outside this lock
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acknowledges a message.
     *
     * <p>Scoped to <em>this</em> session's mailbox, which is what makes the brief's "removed
     * only after the correct recipient acknowledges it" structurally true rather than a
     * check someone remembered to write: a client acking someone else's message id never
     * holds a reference to that mailbox, so it cannot remove anything.
     *
     * @return true if this mailbox actually held the id. False covers both a stale ack and
     *         another client's id, which are indistinguishable here and both harmless.
     */
    boolean ack(String messageId) {
        lock.lock();
        try {
            return mailbox.ack(messageId);
        } finally {
            lock.unlock();
        }
    }

    /** Messages waiting to be delivered. Reported to a client in its REGISTERED frame. */
    public int pendingCount() {
        lock.lock();
        try {
            return mailbox.pendingCount();
        } finally {
            lock.unlock();
        }
    }

    /** Pending plus inflight — everything retained for this identity. */
    public int mailboxSize() {
        lock.lock();
        try {
            return mailbox.size();
        } finally {
            lock.unlock();
        }
    }

    /** Delivered but not yet acknowledged. */
    public int inflightCount() {
        lock.lock();
        try {
            return mailbox.inflightCount();
        } finally {
            lock.unlock();
        }
    }

    /** The currently bound connection, or null when offline. */
    public ClientConnection connection() {
        lock.lock();
        try {
            return connection;
        } finally {
            lock.unlock();
        }
    }

    public boolean isOnline() {
        return connection() != null;
    }

    @Override
    public String toString() {
        return "ClientSession[" + clientId + (isOnline() ? ", online]" : ", offline]");
    }
}
