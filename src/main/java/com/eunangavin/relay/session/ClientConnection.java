package com.eunangavin.relay.session;

import com.eunangavin.relay.protocol.Frame;

/**
 * What the relay core needs from a connection
 * This is not a socket.
 *
 * This interface is the seam that keeps the dependency arrow pointing one way:
 *
 *     net  ──────▶  session  ──────▶  protocol
 *   (sockets,      (identity,        (frames,
 *    threads)       state)            codec)
 *
 * net.Connection implements this, so the {@code session} package never imports
 * {@code java.net}. That is not tidiness for its own sake: it is what lets
 * {@link ClientRegistry}, {@link ClientSession} and the mailbox be unit-tested against a
 * fake with no sockets, no threads and no timing.
 */
public interface ClientConnection {

    /**
     * Hands a frame to this connection's outbound queue.
     *
     * Never blocks. The caller is another client's reader thread, and blocking
     * here would let a slow reader stall the client sending to it.
     * This failure is called out in the brief.
     *
     * @return false if the queue is full or the connection is closed.
     */
    boolean offer(Frame frame);

    /** Closes the connection. Idempotent — several threads may reach it at once. */
    void close(String reason);

    /** The session this connection registered as, or null before a successful REGISTER. */
    ClientSession session();

    /** Called once, by the registry, when registration succeeds. */
    void bind(ClientSession session);

    /** Short identifier for logs, e.g. {@code conn-3}. */
    String label();
}
