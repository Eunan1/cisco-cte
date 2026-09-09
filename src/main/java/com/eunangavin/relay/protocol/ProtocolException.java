package com.eunangavin.relay.protocol;

/**
 * A protocol-level fault carrying the {@link ErrorCode} the peer should be told about.
 *
 * <p><b>Deliberately a checked exception.</b> Extending {@link Exception} rather than
 * {@link RuntimeException} forces every caller to make a decision at the call site:
 * answer the peer with an {@code ERROR} frame, or close the connection.
 *
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
