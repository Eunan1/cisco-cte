package com.eunangavin.relay.protocol;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Converts Frame objects to and from the bytes that travel over a TCP socket.
 *
 * Why this class exists
 * TCP delivers a continuous, ordered stream of bytes. It guarantees that every
 * byte arrives and that it arrives in order — but it says nothing about where one
 * application message ends and the next begins. TCP splits the stream into its own
 * segments sized by MTU, congestion window, and Nagle's algorithm; those boundaries have
 * nothing to do with our frames.
 *
 * Three  writeFrame calls can arrive as one read, or one frame can arrive across three reads.
 *
 * Framing is the layer that puts message boundaries back. This class is that
 * layer, and it is the only place in the codebase that thinks in bytes — every other
 * class deals in Frame objects.
 *
 * Wire format
 *   +--------------------+----------------------------------+
 *   | 4-byte length (BE) |  UTF-8 JSON body (length bytes)  |
 *   +--------------------+----------------------------------+
 *
 *   e.g. Frame.Send("m1", "bob", "hi") encodes to 62 bytes on the wire:
 *
 *   00 00 00 3A  7B 22 74 79 70 65 22 3A 22 53 45 4E 44 22 ...
 *   |         |  |  "  t  y  p  e  "  :  "  S  E  N  D  "
 *   +-- 58 ---+  +---------------- 58 bytes ------------------
 *
 * Three conventions, both ends must agree on all of them:
 * <ol>
 *   <li><b>Four bytes.</b> A Java {@code int}, so {@code writeInt}/{@code readInt} handle
 *       it directly. It can express ~2 GiB — far above our 64 KiB policy limit, on
 *       purpose, so the limit can move later without changing the wire format.</li>
 *   <li><b>Big-endian</b> (most significant byte first) — network byte order, and what
 *       {@code writeInt}/{@code readInt} are specified to produce. Read {@code 00 00 00 3A}
 *       as little-endian and you get 973,078,528 instead of 58.</li>
 *   <li><b>Length excludes the prefix itself.</b> After reading the 4 prefix bytes, read
 *       exactly {@code length} more. The alternative convention (total size including the
 *       prefix) is equally valid but incompatible — mixing them desynchronises the stream
 *       permanently, because every later boundary is then wrong too.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * This class holds no mutable state, so one instance is safe to share across all
 * connections. {@link ObjectMapper} is documented as thread-safe once configured, which is
 * why it is a configured-once static. The {@code DataInputStream}/{@code DataOutputStream}
 * passed in are <em>not</em> shared — each belongs to one connection, read by that
 * connection's reader thread and written by its writer thread, and never touched by two
 * threads at once.
 */
public final class FrameCodec {

    /**
     * Configured once, shared, never mutated after construction.
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled deliberately: a newer client that
     * adds a field to a frame will not break an older server, which is forward
     * compatibility for one line of configuration. It is also what makes "add a field to
     * DELIVER" a safe change rather than a breaking one.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * Maximum size of the JSON body, in bytes — the 4-byte prefix is not counted, matching
     * the "length excludes the prefix" convention above. Injected rather than hard-coded so
     * tests can use a tiny limit and prove the bound without building a 64 KiB frame.
     */
    private final int maxFrameBytes;

    public FrameCodec(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Encodes one frame and writes it as prefix + body.
     *
     * @throws ProtocolException if the encoded frame exceeds the limit. Nothing has been
     *                           written when this is thrown, so the stream stays usable.
     */
    public void writeFrame(DataOutputStream out, Frame frame) throws IOException, ProtocolException {

        // Object -> JSON bytes. Jackson's byte-array methods are UTF-8 by specification,
        // so there is no platform-default-charset trap here (unlike new String(bytes)).
        byte[] body = MAPPER.writeValueAsBytes(frame);

        // Bound check BEFORE writing anything at all. If we wrote the prefix first and
        // only then discovered the body was too large, we would have committed a promise
        // to the peer that we cannot keep — the stream would be desynchronised and the
        // connection unusable. Checking first means a rejected frame is a no-op.
        if (body.length > maxFrameBytes) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE,
                    "encoded frame " + body.length + " exceeds limit " + maxFrameBytes);
        }

        // writeInt is specified big-endian, so we get network byte order for free.
        // The value is the BODY length; the 4 prefix bytes are not included.
        out.writeInt(body.length);
        out.write(body);

        // Without this the frame can sit in a BufferedOutputStream indefinitely while the
        // peer waits for a message we believe we already sent. In STORY-2 that presents as
        // "my client hangs forever" and is genuinely hard to spot.
        out.flush();
    }

    /**
     * Reads the next frame, blocking until a whole one has arrived.
     *
     * @return the frame, or {@code null} if the peer closed the connection cleanly between
     *         frames. Null is the normal end-of-stream signal, not a failure.
     * @throws ProtocolException if the peer sent something we will not accept. The caller
     *                           decides whether to reply with ERROR or close.
     */
    public Frame readFrame(DataInputStream in) throws IOException, ProtocolException {

        int length;
        try {
            // Reads exactly 4 bytes and assembles them big-endian.
            length = in.readInt();
        } catch (EOFException eof) {
            // The peer closed between frames. That is an orderly disconnect, not an error,
            // and logging it as one would make every clean shutdown look like a fault.
            //
            // Known imprecision: a peer that dies after sending 2 of the 4 prefix bytes
            // also lands here and is reported as a clean close. Distinguishing the two
            // would mean reading the prefix a byte at a time and tracking how many we got.
            // Not worth the complexity at this scale, but worth knowing it is approximate.
            return null;
        }

        // This int arrived over the network, so it is hostile until proven otherwise, and
        // the check must happen BEFORE the allocation on the next line:
        //   - negative  -> new byte[-1] throws NegativeArraySizeException, an unchecked
        //                  crash from deep inside the read loop rather than a clean fault
        //   - enormous  -> new byte[2_000_000_000] is an immediate OutOfMemoryError, so a
        //                  single 4-byte write from one client kills the server for everyone
        if (length < 0 || length > maxFrameBytes) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE,
                    "declared frame length " + length + " outside [0, " + maxFrameBytes + "]");
        }

        byte[] body = new byte[length];

        // readFully, NOT read. read(byte[]) returns however many bytes happen to be
        // available right now — possibly one — and is under no obligation to fill the
        // array. readFully loops internally until the array is full or the stream ends.
        // This single call is what handles TCP segmentation, and using read() here is the
        // most common bug in hand-rolled framing: it passes every localhost test and
        // corrupts data the moment a frame is split across segments.
        in.readFully(body);

        try {
            // JSON bytes -> object. Jackson picks the record from the "type" discriminator
            // via the @JsonSubTypes table on Frame.
            return MAPPER.readValue(body, Frame.class);
        } catch (JacksonException e) {
            // JacksonException extends IOException, so this catch MUST come before any
            // catch of IOException or a parse failure would be misread as a broken socket.
            // Those need opposite reactions: a parse failure is the peer's fault and we can
            // still talk to them; a broken socket means there is nobody left to talk to.
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, e.getOriginalMessage());
        }
    }
}
