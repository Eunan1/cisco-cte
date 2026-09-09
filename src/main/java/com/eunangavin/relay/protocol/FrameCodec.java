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
 *
 *
 * Framing is the layer that puts message boundaries back. This class is that layer, and
 * it is the only place in the codebase that thinks in bytes — every other class deals in
 * Frame objects.
 *
 * Wire format:
 *   +--------------------+----------------------------------+
 *   | 4-byte length (BE) |  UTF-8 JSON body (length bytes)  |
 *   +--------------------+----------------------------------+
 *
 *   Frame.Send("m1", "bob", "hi") encodes to 62 bytes on the wire:
 *
 *   00 00 00 3A  7B 22 74 79 70 65 22 3A 22 53 45 4E 44 22 ...
 *   |         |  |  "  t  y  p  e  "  :  "  S  E  N  D  "
 *   +-- 58 ---+  +---------------- 58 bytes ------------------
 *
 * Three conventions, and both ends must agree on all of them:
 * Four bytes, Big-endian (most significant byte first) & Length excludes the prefix itself.
 *
 */
public final class FrameCodec {

    // FAIL_ON_UNKNOWN_PROPERTIES: Newer clients that add a field to a frame will not break an older server
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

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

        byte[] body = MAPPER.writeValueAsBytes(frame);

        // Bound check before writing anything
        if (body.length > maxFrameBytes) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE,
                    "encoded frame " + body.length + " exceeds limit " + maxFrameBytes);
        }

        out.writeInt(body.length);
        out.write(body);

        // Without this the frame sits in the BufferedOutputStream while the peer waits for
        // a message we believe we already sent. Presents as "my client hangs forever".
        out.flush();
    }

    /**
     * Reads the next frame, blocking until a whole one has arrived.
     *
     * @return the frame, or null if the peer closed the connection cleanly between
     *         frames. Null is the normal end-of-stream signal, not a failure.
     * @throws ProtocolException if the peer sent something we will not accept. The caller
     *                           decides whether to reply with ERROR or close.
     */
    public Frame readFrame(DataInputStream in) throws IOException, ProtocolException {

        int length;
        try {
            length = in.readInt();
        } catch (EOFException eof) {
            // An orderly disconnect, not an error. Known imprecision: a peer that dies
            // after sending 2 of the 4 prefix bytes also lands here and is reported as a
            // clean close.
            return null;
        }

        if (length < 0 || length > maxFrameBytes) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE,
                    "declared frame length " + length + " outside [0, " + maxFrameBytes + "]");
        }

        byte[] body = new byte[length];

        // readFully, NOT read. read(byte[]) returns whatever is available right now and is
        // under no obligation to fill the array. Loops until body is full
        in.readFully(body);

        try {
            return MAPPER.readValue(body, Frame.class);
        } catch (JacksonException e) {
            // JacksonException extends IOException, so this catch MUST come first or a
            // parse failure would be misread as a broken socket
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, e.getOriginalMessage());
        }
    }
}
