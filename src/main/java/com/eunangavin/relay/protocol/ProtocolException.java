package com.eunangavin.relay.protocol;

/**
 * A protocol-level fault carrying the {@link ErrorCode} the peer should be told about.
 *
 * <p><b>Deliberately a checked exception.</b> Extending {@link Exception} rather than
 * {@link RuntimeException} forces every caller to make a decision at the call site:
 * answer the peer with an {@code ERROR} frame, or close the connection. Those are
 * genuinely different reactions depending on the code (see {@link ErrorCode}), and an
 * unchecked exception would let that decision be skipped by accident.
 *
 * <p>Note it does <em>not</em> extend {@link java.io.IOException}. That separation is the
 * point: an {@code IOException} means the socket broke, a {@code ProtocolException} means
 * the socket is fine but the peer sent us something we will not accept. Those want
 * different handling, so they get different types.
 */
public class ProtocolException extends Exception {

    private final ErrorCode code;

    public ProtocolException(ErrorCode code, String reason) {
        // Prefixing the message with the code means logs and test failures identify the
        // fault without the reader having to unpack the exception.
        super(code + ": " + reason);
        this.code = code;
    }

    /** The code to report to the peer. Accessor is record-style to match the Frame types. */
    public ErrorCode code() {
        return code;
    }
}
