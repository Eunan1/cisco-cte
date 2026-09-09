package com.eunangavin.relay.session;

import com.eunangavin.relay.protocol.Frame;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A logical client. Identity outlives the connection.
 *
 * A {@code ClientSession} is created on first registration and then persists:
 * The socket is a nullable field on it, not the other way round.
 * Disconnecting sets that field to null. Reconnecting sets it again. Nothing else is lost.
 *
 * The mailbox hangs off this object, and allows for requirements
 * 5: retention while offline
 * 6: reattachment by name
 * 7: redelivery of unacked messages
 *
 * Had the mailbox hung off the connection instead, every one of them would have been a rewrite.
 *
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
     * Takeover, not rejection. If a connection already holds this name we replace
     * it rather than refusing the newcomer.
     *
     * @return the evicted connection, or null if none. <b>The caller must close it outside
     *         the lock</b> — closing does socket I/O, and holding a lock across I/O is how
     *         one slow socket becomes everyone's problem.
     */
    ClientConnection attach(ClientConnection incoming) {
        lock.lock();
        try {
            ClientConnection evicted = this.connection;
            // Anything inflight on the replaced connection was never acknowledged, so it
            // goes back to pending — otherwise nobody is left to ack it.
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
     * The identity check is load-bearing. Consider:
     *
     *   t0  connection A registers as "alice"       connection = A
     *   t1  connection B registers as "alice"       connection = B, A evicted
     *   t2  A's reader loop exits and detaches      ← must NOT null out B
     *
     * Without the check, a slow disconnect from an evicted connection silently kills the
     * healthy connection that replaced it moments earlier. The bug is invisible to any test
     * that does not reconnect quickly.
     */
    void detach(ClientConnection leaving) {
        lock.lock();
        try {
            if (this.connection == leaving) {
                // Requeue BEFORE clearing the connection. Requirement 7: delivered but
                // unacknowledged must be available again on reconnect.
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
     * @return a connection the caller must close because its outbound queue is full,
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
     * Scoped to this session's mailbox, which is what makes the brief's "removed
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
