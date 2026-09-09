package com.eunangavin.relay.net;

import com.eunangavin.relay.Log;
import com.eunangavin.relay.protocol.Frame;
import com.eunangavin.relay.protocol.FrameCodec;
import com.eunangavin.relay.protocol.ProtocolException;
import com.eunangavin.relay.session.ClientConnection;
import com.eunangavin.relay.session.ClientSession;
import com.eunangavin.relay.session.RelayService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One TCP connection: a socket, a reader thread, a writer thread, and a bounded queue
 * between them.
 *
 * <h2>Why two threads</h2>
 * The reader blocks on the socket; the writer blocks on the queue. Splitting them is what
 * makes the brief's "a slow client does not block unrelated clients" requirement true.
 *
 * <p>If delivery wrote straight to the recipient's socket it would do so on whichever
 * thread was delivering, which is the <em>sender's</em> reader thread. A
 * recipient that has stopped reading fills its kernel receive buffer, TCP's window closes,
 * our {@code write()} blocks, and now Alice's connection is stalled by Bob's problem.
 *
 * <p>With a writer thread and a bounded queue, delivery calls {@link #offer} and returns
 * immediately. Backpressure stays inside this one connection.
 *
 * <h2>Why the queue is bounded, and what happens when it fills</h2>
 * Three options existed: block the offering thread (reintroduces the exact problem), drop
 * the frame (silently loses a message we may already have called ACCEPTED), or drop the
 * connection. We drop the connection — see {@code RelayService.sendOrDrop}. A client this
 * far behind is not keeping up, and its session outlives the socket, so reconnecting
 * recovers everything.
 *
 * <h2>Closing without losing the last frame</h2>
 * Almost every close here follows a frame we actually want delivered — the takeover notice,
 * a MALFORMED_FRAME error, the SHUTDOWN broadcast. Interrupting the writer straight away
 * discards whatever is still queued, so the peer sees an unexplained EOF instead of the
 * reason.
 *
 * <p>So {@link #close} is <b>graceful</b>: it stops accepting new frames, enqueues a poison
 * pill, and waits briefly for the writer to drain everything ahead of it and exit.
 * {@link #closeNow} is the hard version, used when the queue is jammed or the grace expires.
 *
 * <p>Waiting on <em>queue emptiness</em> would not work, and it is worth knowing why:
 * {@code take()} removes a frame before writing it, so the queue reads empty while the
 * write is still in flight. The writer signalling its own exit is the only reliable
 * indication that everything actually reached the socket.
 */
public final class Connection implements ClientConnection {

    /**
     * Sentinel telling the writer to stop. Compared by <em>identity</em> and never
     * transmitted, so it needs no distinct type — which matters because {@link Frame} is
     * sealed and adding a case for it would put a fake operation into the protocol.
     */
    private static final Frame POISON = new Frame.Shutdown("__writer-stop__");

    /** How long a graceful close waits for the writer to flush before forcing the socket. */
    private static final Duration WRITER_DRAIN_GRACE = Duration.ofMillis(500);

    private final String label;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final FrameCodec codec;
    private final BlockingQueue<Frame> outbound;
    private final Consumer<Connection> onClosed;

    /** Set first: stops new frames being accepted while the writer drains. */
    private final AtomicBoolean closing = new AtomicBoolean(false);
    /** Set once the socket is actually torn down. */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Counted down by the writer as it exits — the signal a graceful close waits on. */
    private final CountDownLatch writerDone = new CountDownLatch(1);

    private volatile ClientSession session;
    private volatile Thread writerThread;

    Connection(String label, Socket socket, FrameCodec codec, int queueCapacity,
               Consumer<Connection> onClosed) throws IOException {
        this.label = label;
        this.socket = socket;
        this.codec = codec;
        this.outbound = new ArrayBlockingQueue<>(queueCapacity);
        this.onClosed = onClosed;

        // Buffered before the Data* wrappers, or every writeInt is its own syscall. The
        // codec's flush() is what pushes a completed frame out.
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    // ------------------------------------------------------------------ ClientConnection

    @Override
    public boolean offer(Frame frame) {
        // Never blocks. See the class comment - the caller may be another client's thread.
        return !closing.get() && outbound.offer(frame);
    }

    @Override
    public ClientSession session() {
        return session;
    }

    @Override
    public void bind(ClientSession session) {
        this.session = session;
    }

    @Override
    public String label() {
        return label;
    }

    // ------------------------------------------------------------------ threads

    /**
     * Reads frames until the peer closes, the socket breaks, or we shut it down.
     * Runs on its own virtual thread.
     */
    void runReader(RelayService service) {
        try {
            Frame frame;
            // readFrame returns null on a clean close between frames - the normal exit.
            while ((frame = codec.readFrame(in)) != null) {
                service.onFrame(this, frame);
            }
        } catch (ProtocolException e) {
            // Stream-level fault: we can no longer locate the next frame boundary, so tell
            // them and close. The graceful close is what gets this last frame delivered.
            Log.warn("%s protocol fault: %s", label, e.getMessage());
            offer(new Frame.Error(e.code(), e.getMessage()));
        } catch (IOException e) {
            // The peer vanished, or we closed the socket ourselves during shutdown.
            if (!closing.get()) {
                Log.info("%s connection lost: %s", label, e.getMessage());
            }
        } finally {
            service.onDisconnect(this);
            close("reader ended");
        }
    }

    /** Drains the outbound queue and writes frames. Runs on its own virtual thread. */
    void runWriter() {
        writerThread = Thread.currentThread();
        try {
            while (true) {
                Frame frame = outbound.take();      // interruptible
                if (frame == POISON) {              // identity, deliberately not equality
                    return;                         // everything ahead of it is written
                }
                codec.writeFrame(out, frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // restore the flag, do not swallow it
        } catch (IOException e) {
            if (!closing.get()) {
                Log.info("%s write failed: %s", label, e.getMessage());
            }
        } catch (ProtocolException e) {
            // Something oversized. Our bug, not the peer's.
            Log.warn("%s could not encode a frame: %s", label, e.getMessage());
        } finally {
            writerDone.countDown();
            // closeNow, not close: close() waits on writerDone, and we are the writer.
            closeNow("writer ended");
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Graceful close: stop accepting frames, let the writer flush what is already queued,
     * then tear the socket down. Idempotent and safe from any thread.
     *
     * <p>If the queue is full the poison pill cannot be enqueued, the grace expires, and we
     * force the close. That is the right outcome for a slow consumer — it is already too far
     * behind to be worth waiting for.
     */
    @Override
    public void close(String reason) {
        if (closing.compareAndSet(false, true)) {
            outbound.offer(POISON);   // best effort; a full queue means we force below
        }

        // The writer must never wait on itself.
        if (Thread.currentThread() != writerThread) {
            try {
                writerDone.await(WRITER_DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeNow(reason);
    }

    /**
     * Hard close. Anything still queued is lost.
     *
     * <p>Two threads are parked in different ways and both must be freed:
     * <ul>
     *   <li>The writer is parked on {@code queue.take()} — interruptible, so
     *       {@code interrupt()} frees it.</li>
     *   <li>The reader is parked in a socket read. <b>Closing the socket</b> frees it, via
     *       SocketException.</li>
     * </ul>
     *
     * <p>The received wisdom is that interrupting cannot free a thread blocked in socket
     * I/O, and for a <em>platform</em> thread that is true. It is <b>not</b> true here:
     * our readers are <em>virtual</em> threads, which use the NIO-backed socket
     * implementation and unblock on interrupt with a SocketException. Verified rather than
     * assumed — a small probe showed a platform thread staying blocked while a virtual
     * thread was released by the identical interrupt.
     *
     * <p>We close the socket anyway, for two reasons: it is correct regardless of thread
     * type, and we want the file descriptor released rather than merely the thread freed.
     */
    void closeNow(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closing.set(true);
        Log.info("%s closing (%s)", label, reason);

        Thread writer = writerThread;
        if (writer != null && writer != Thread.currentThread()) {
            writer.interrupt();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        onClosed.accept(this);
    }

    public boolean isClosed() {
        return closed.get();
    }
}
