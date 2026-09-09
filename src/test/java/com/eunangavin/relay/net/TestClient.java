package com.eunangavin.relay.net;

import com.eunangavin.relay.protocol.Frame;
import com.eunangavin.relay.protocol.FrameCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A minimal client for tests. STORY-5 replaces this with the real {@code RelayClient}.
 *
 * <p><b>No background thread.</b> Reads are synchronous, with the socket's own
 * {@code SO_TIMEOUT} providing the bound — so a server that never replies fails the test
 * with a timeout rather than hanging it, and there is no reader thread to coordinate with.
 * That keeps every test deterministic without a single {@code Thread.sleep}.
 */
final class TestClient implements AutoCloseable {

    /** Generous enough not to be flaky, short enough that a hang fails fast. */
    private static final int READ_TIMEOUT_MS = 2000;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final FrameCodec codec = new FrameCodec(64 * 1024);

    private TestClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    static TestClient connect(int port) throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        return new TestClient(socket);
    }

    void send(Frame frame) {
        try {
            codec.writeFrame(out, frame);
        } catch (Exception e) {
            throw new AssertionError("failed to send " + frame, e);
        }
    }

    /** Writes bytes straight to the socket, bypassing the codec — for malformed-input tests. */
    void sendRaw(byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            throw new AssertionError("failed to send raw bytes", e);
        }
    }

    /** @return the next frame, or null if the server closed cleanly. */
    Frame receive() {
        try {
            return codec.readFrame(in);
        } catch (SocketTimeoutException e) {
            return fail("timed out after " + READ_TIMEOUT_MS + "ms waiting for a frame");
        } catch (Exception e) {
            throw new AssertionError("failed to read a frame", e);
        }
    }

    <T extends Frame> T expect(Class<T> type) {
        Frame frame = receive();
        assertInstanceOf(type, frame, "unexpected frame");
        return type.cast(frame);
    }

    /**
     * Asserts the server closes this connection.
     *
     * <p>A close surfaces either as a clean end of stream (null) or as a connection reset —
     * which of the two depends on timing and on the platform, so accepting both is correct
     * rather than lax.
     */
    void expectClosed() {
        try {
            Frame frame = codec.readFrame(in);
            if (frame != null) {
                fail("expected the connection to be closed but received " + frame);
            }
        } catch (SocketTimeoutException e) {
            fail("connection was not closed within " + READ_TIMEOUT_MS + "ms");
        } catch (IOException e) {
            // Connection reset - also a close.
        } catch (Exception e) {
            throw new AssertionError("unexpected failure while awaiting close", e);
        }
    }

    /** Stops reading, so the server's outbound queue to this client backs up. */
    void stopReading() throws IOException {
        socket.shutdownInput();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Test teardown; nothing useful to do.
        }
    }
}
