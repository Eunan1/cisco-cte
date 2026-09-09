package com.eunangavin.relay.session;

import com.eunangavin.relay.protocol.Frame;

/**
 * What the relay core needs from a connection — deliberately not a socket.
 *
 * <p>This interface is the seam that keeps the dependency arrow pointing one way:
 *
 * <pre>
 *     net  ──────▶  session  ──────▶  protocol
 *   (sockets,      (identity,        (frames,
 *    threads)       state)            codec)
 * </pre>
 *
 * <p>{@code net.Connection} implements this, so the {@code session} package never imports
 * {@code java.net}. That is not tidiness for its own sake: it is what lets
 * {@link ClientRegistry}, {@link ClientSession} and (in STORY-3) the mailbox be unit-tested
 * against a fake with no sockets, no threads and no timing — the difference between fast
 * deterministic tests and slow flaky ones.
 */
public interface ClientConnection {

    /**
     * Hands a frame to this connection's outbound queue.
     *
     * <p><b>Never blocks.</b> In STORY-3 the caller is another client's reader thread, and
     * blocking here would let a slow reader stall the client sending to it — exactly the
     * failure the brief calls out.
     *
     * @return false if the queue is full or the connection is closed. The caller decides
     *         what that means; for delivery it means the client is too far behind to keep.
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
