package com.eunangavin.relay.client;

import com.eunangavin.relay.protocol.ErrorCode;
import com.eunangavin.relay.protocol.Frame;
import com.eunangavin.relay.protocol.FrameCodec;
import com.eunangavin.relay.protocol.ProtocolException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A client for the relay. Used by the CLI and by tests; it knows nothing about a console.
 *
 * <h2>Two blocking inputs, two threads</h2>
 * A thread that calls a blocking read is suspended by the OS until data arrives — it burns
 * no CPU, but it also cannot do anything else. A client has two independent sources that
 * both block:
 *
 * <pre>
 *   readFrame(socket)      blocks until the SERVER sends
 *   System.in.readLine()   blocks until the USER types      (in RelayCli)
 * </pre>
 *
 * One thread can serve one blocking source. So the caller's thread waits on the user, and
 * a reader thread here waits on the socket. Without that split, a {@code DELIVER} arriving
 * while you were typing would sit unread in the kernel buffer until you happened to press
 * Enter — not lost, but arbitrarily late, which in a demo looks like a broken server.
 *
 * <p>This is the same problem the server solves with a reader and a writer thread, seen
 * from the other end. Note the client needs <em>no</em> writer thread: the server writes to
 * one client on another client's thread, so it must decouple, whereas we only write when
 * the user types — one message at a time, at human speed.
 *
 * <h2>Pushes versus replies</h2>
 * The server sends two kinds of frame, and confusing them is the trap in writing a client:
 *
 * <pre>
 *   you send:  SEND m1
 *   you expect: ACCEPTED m1
 *   what may arrive: DELIVER (from someone else, pushed right then), THEN ACCEPTED m1
 * </pre>
 *
 * A client that sends and then reads "the next frame" as its response would treat that
 * DELIVER as the answer and lose it. So the reader thread <b>routes by kind</b>: pushes
 * ({@code DELIVER}, {@code SHUTDOWN}) go to the listener, replies go to a queue that the
 * calling thread drains.
 *
 * <p><b>Its limitation, worth stating:</b> this works because a client has one operation in
 * flight at a time. Two concurrent sends would each take whichever reply arrived first,
 * possibly the wrong one. That is precisely what a {@code correlationId} on the envelope
 * solves — deliberately deferred, and listed under known limitations. One instance of this
 * class is intended for use by one thread at a time.
 */
public final class RelayClient implements AutoCloseable {

    /** Long enough not to be flaky, short enough that a silent server fails visibly. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    /** Frames the server pushes without being asked. */
    public interface MessageListener {
        void onDeliver(Frame.Deliver deliver);

        void onShutdown(Frame.Shutdown shutdown);

        /** Fires exactly once, when the connection ends for any reason. */
        void onDisconnected(String reason);
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final FrameCodec codec;

    /** Replies to things we asked for. Pushes never land here — see the class comment. */
    private final BlockingQueue<Frame> replies = new LinkedBlockingQueue<>();

    /** Guards the output stream. One writer at a time, even if a caller misuses the class. */
    private final ReentrantLock writeLock = new ReentrantLock();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean disconnectReported = new AtomicBoolean(false);
    private final AtomicInteger nextMessageId = new AtomicInteger(1);

    private volatile String clientId;

    private RelayClient(Socket socket, int maxFrameBytes) throws IOException {
        this.socket = socket;
        this.codec = new FrameCodec(maxFrameBytes);
        // Buffered before the Data* wrappers, exactly as the server's Connection does.
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public static RelayClient connect(String host, int port, MessageListener listener)
            throws IOException {
        RelayClient client = new RelayClient(new Socket(host, port), 64 * 1024);
        // Virtual threads are daemon threads, so this cannot keep the JVM alive after quit.
        Thread.ofVirtual().name("relay-client-reader").start(() -> client.readLoop(listener));
        return client;
    }

    // ------------------------------------------------------------------ operations

    public Frame.Registered register(String clientId) throws IOException, ProtocolException {
        writeFrame(new Frame.Register(clientId));
        Frame reply = awaitReply();
        if (reply instanceof Frame.Registered registered) {
            this.clientId = clientId;
            return registered;
        }
        throw asProtocolException(reply);
    }

    /**
     * Sends a message and returns the server's verdict.
     *
     * <p>A {@code REJECTED} is <b>returned, not thrown</b>: an unknown recipient or a full
     * mailbox is a normal, expected outcome that the caller must handle, not an exceptional
     * one.
     *
     * <p>The generated id is prefixed with our own client id. Ids are unique per
     * <em>recipient mailbox</em>, not globally — so two clients both numbering from 1 and
     * sending to the same person would collide with DUPLICATE_MESSAGE_ID.
     *
     * @return a {@link Frame.Accepted} or a {@link Frame.Rejected}
     */
    public Frame send(String to, String payload) throws IOException, ProtocolException {
        if (clientId == null) {
            throw new IllegalStateException("register() before send()");
        }
        String messageId = clientId + "-" + nextMessageId.getAndIncrement();
        writeFrame(new Frame.Send(messageId, to, payload));

        Frame reply = awaitReply();
        if (reply instanceof Frame.Accepted || reply instanceof Frame.Rejected) {
            return reply;
        }
        throw asProtocolException(reply);
    }

    public Frame.AckOk ack(String messageId) throws IOException, ProtocolException {
        writeFrame(new Frame.Ack(messageId));
        Frame reply = awaitReply();
        if (reply instanceof Frame.AckOk ackOk) {
            return ackOk;
        }
        throw asProtocolException(reply);
    }

    // ------------------------------------------------------------------ reader thread

    private void readLoop(MessageListener listener) {
        String reason = "connection closed";
        try {
            Frame frame;
            while ((frame = codec.readFrame(in)) != null) {
                switch (frame) {
                    // Pushes: unsolicited, handled by the listener.
                    case Frame.Deliver deliver -> listener.onDeliver(deliver);
                    case Frame.Shutdown shutdown -> {
                        listener.onShutdown(shutdown);
                        reason = "server shut down: " + shutdown.reason();
                    }
                    // Everything else is an answer to something we asked for.
                    default -> replies.put(frame);
                }
            }
        } catch (IOException e) {
            reason = closed.get() ? "closed by client" : "connection lost: " + e.getMessage();
        } catch (ProtocolException e) {
            reason = "protocol error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        } finally {
            // Exactly once, however we got here.
            if (disconnectReported.compareAndSet(false, true)) {
                listener.onDisconnected(reason);
            }
        }
    }

    /**
     * Waits for the next reply.
     *
     * <p>Bounded on purpose. An unbounded {@code take()} would turn a server-side bug into a
     * terminal that hangs with no explanation.
     */
    private Frame awaitReply() throws IOException {
        try {
            Frame reply = replies.poll(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (reply == null) {
                throw new IOException("no response from server within " + RESPONSE_TIMEOUT);
            }
            return reply;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while awaiting a response", e);
        }
    }

    private void writeFrame(Frame frame) throws IOException {
        writeLock.lock();
        try {
            codec.writeFrame(out, frame);
        } catch (ProtocolException e) {
            // Only if we tried to send something oversized — our bug, not the server's.
            throw new IOException("could not encode " + frame, e);
        } finally {
            writeLock.unlock();
        }
    }

    private static ProtocolException asProtocolException(Frame reply) {
        if (reply instanceof Frame.Error error) {
            return new ProtocolException(error.code(), error.reason());
        }
        return new ProtocolException(ErrorCode.MALFORMED_FRAME, "unexpected reply: " + reply);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Idempotent: the user typing "quit" and the server closing can happen at once. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.close();   // unblocks the reader thread's readFrame
        } catch (IOException ignored) {
            // Already gone.
        }
    }
}
