package com.eunangavin.relay.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Everything here runs against in-memory byte arrays. No sockets, no threads, no timing —
 * which is the whole reason the codec was built as its own story: these are the only
 * fully deterministic tests in the project, and every framing bug is caught here rather
 * than tangled up with concurrency later.
 */
class FrameCodecTest {

    /** Small on purpose: proving the bound should not require building a 64 KiB frame. */
    private static final int MAX = 1024;

    private final FrameCodec codec = new FrameCodec(MAX);

    // ------------------------------------------------------------------ helpers

    private byte[] encode(Frame frame) throws Exception {
        var bytes = new ByteArrayOutputStream();
        codec.writeFrame(new DataOutputStream(bytes), frame);
        return bytes.toByteArray();
    }

    private Frame decode(byte[] bytes) throws Exception {
        return codec.readFrame(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    /** Builds a stream containing a length prefix that lies about what follows. */
    private static DataInputStream prefixed(int declaredLength, byte[] body) {
        var buf = ByteBuffer.allocate(4 + body.length);
        buf.putInt(declaredLength);          // ByteBuffer is big-endian by default
        buf.put(body);
        return new DataInputStream(new ByteArrayInputStream(buf.array()));
    }

    private static DataInputStream framed(String json) {
        return prefixed(json.getBytes(StandardCharsets.UTF_8).length,
                json.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ round trip

    static Stream<Frame> everyFrameType() {
        return Stream.of(
                new Frame.Register("alice"),
                new Frame.Registered("alice", 3),
                new Frame.Send("m1", "bob", "hello"),
                new Frame.Accepted("m1"),
                new Frame.Rejected("m1", ErrorCode.MAILBOX_FULL, "bob's mailbox is full"),
                new Frame.Deliver("m1", "alice", "hello"),
                new Frame.Ack("m1"),
                new Frame.AckOk("m1"),
                new Frame.Error(ErrorCode.NOT_REGISTERED, "register first"),
                new Frame.Shutdown("maintenance"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyFrameType")
    @DisplayName("every frame type survives encode then decode")
    void roundTripsEveryFrameType(Frame original) throws Exception {
        // Records give us equals(), so the whole assertion is one line.
        assertEquals(original, decode(encode(original)));
    }

    @Test
    @DisplayName("payload with non-ASCII characters survives intact")
    void roundTripsNonAsciiPayload() throws Exception {
        // Guards against anyone reintroducing a platform-default charset. On a
        // windows-1252 default these characters would corrupt.
        var original = new Frame.Send("m1", "bob", "héllo wörld é中文 🚀");
        assertEquals(original, decode(encode(original)));
    }

    // ------------------------------------------------------------------ segmentation

    /**
     * Returns at most one byte per read call, which reproduces TCP segmentation
     * deterministically on a single thread with no network involved.
     *
     * <p>This is the regression test for using {@code read(byte[])} instead of
     * {@code readFully(byte[])} — the most common bug in hand-rolled framing, and one that
     * passes every normal localhost test.
     */
    private static final class DribbleInputStream extends FilterInputStream {
        DribbleInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(len, 1));
        }
    }

    @Test
    @DisplayName("a frame split one byte per read still decodes")
    void readsFrameSplitAcrossReads() throws Exception {
        var original = new Frame.Send("m1", "bob", "a payload long enough to span reads");
        byte[] wire = encode(original);

        var dribbled = new DataInputStream(
                new DribbleInputStream(new ByteArrayInputStream(wire)));

        assertEquals(original, codec.readFrame(dribbled));
    }

    @Test
    @DisplayName("consecutive frames in one stream decode in order")
    void readsMultipleFramesFromOneStream() throws Exception {
        var first = new Frame.Register("alice");
        var second = new Frame.Send("m1", "bob", "hi");

        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        codec.writeFrame(out, first);
        codec.writeFrame(out, second);

        // Proves the boundary really is recoverable: without the prefix the reader would
        // have no idea where the first frame ends.
        var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(first, codec.readFrame(in));
        assertEquals(second, codec.readFrame(in));
        assertNull(codec.readFrame(in), "stream is exhausted");
    }

    // ------------------------------------------------------------------ hostile input

    @Test
    @DisplayName("declared length above the limit is rejected without allocating")
    void rejectsDeclaredLengthAboveLimit() {
        var hostile = prefixed(Integer.MAX_VALUE, new byte[0]);

        // If the bound were checked after the allocation this would be an OutOfMemoryError
        // rather than a clean protocol fault - one 4-byte write killing the whole server.
        var ex = assertThrows(ProtocolException.class, () -> codec.readFrame(hostile));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code());
    }

    @Test
    @DisplayName("negative declared length is rejected, not NegativeArraySizeException")
    void rejectsNegativeDeclaredLength() {
        var hostile = prefixed(-1, new byte[0]);

        var ex = assertThrows(ProtocolException.class, () -> codec.readFrame(hostile));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code());
    }

    // ------------------------------------------------------------------ compatibility

    @Test
    @DisplayName("unknown fields are ignored, so a newer client cannot break us")
    void tolerantOfUnknownFields() throws Exception {
        // A future client adds "sentAt". FAIL_ON_UNKNOWN_PROPERTIES is disabled, so this
        // parses cleanly - that is the forward compatibility the codec buys for one line.
        Frame frame = codec.readFrame(framed(
                "{\"type\":\"SEND\",\"messageId\":\"m1\",\"to\":\"bob\","
                        + "\"payload\":\"hi\",\"sentAt\":\"2026-09-06T12:00:00Z\"}"));

        assertEquals(new Frame.Send("m1", "bob", "hi"), frame);
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    @DisplayName("clean end of stream returns null rather than throwing")
    void returnsNullOnCleanEndOfStream() throws Exception {
        // A peer closing between frames is an orderly disconnect. Treating it as an error
        // would make every normal shutdown look like a fault in the logs.
        assertNull(codec.readFrame(new DataInputStream(new ByteArrayInputStream(new byte[0]))));
    }

}
