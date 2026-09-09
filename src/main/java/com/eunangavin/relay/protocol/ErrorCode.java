package com.eunangavin.relay.protocol;

/**
 * Every way this service can say "no".
 *
 * <p>The brief requires us to "define how the service reports invalid input and resource
 * limits", so the full set is enumerated here rather than scattered as string literals.
 * An enum also means the wire values are fixed at compile time and a typo cannot ship.
 *
 * <p>The grouping below is not just cosmetic — it maps to how the server reacts:
 * <ul>
 *   <li><b>Request-level</b> faults are answered with a {@code REJECTED} or {@code ERROR}
 *       frame and the connection carries on. The frame parsed fine; we simply declined it.</li>
 *   <li><b>Stream-level</b> faults ({@link #FRAME_TOO_LARGE}, {@link #MALFORMED_FRAME})
 *       mean we can no longer trust where the next frame begins, so the connection is
 *       closed. Continuing would read garbage forever.</li>
 * </ul>
 * That asymmetry is the interesting part, and it is worth being able to explain.
 */
public enum ErrorCode {

    // --- Registration -------------------------------------------------------
    /** An operation that requires an identity arrived before REGISTER. */
    NOT_REGISTERED,
    /** This connection has already registered; a second REGISTER is a client bug. */
    ALREADY_REGISTERED,

    // --- Addressing and acknowledgement -------------------------------------
    /** No session exists for the requested recipient. */
    UNKNOWN_RECIPIENT,
    /** A message with this id is already live in the recipient's mailbox. */
    DUPLICATE_MESSAGE_ID,

    // --- Resource limits (request-level: connection survives) ----------------
    /** Recipient's mailbox is at capacity. Reported to the sender, not the recipient. */
    MAILBOX_FULL,
    /** SEND payload exceeds maxPayloadBytes. */
    PAYLOAD_TOO_LARGE,
    /** Server is at maxConnections; sent immediately before closing the new socket. */
    CONNECTION_LIMIT_REACHED,
    /**
     * The client fell far enough behind that its bounded outbound queue filled, so its
     * connection was dropped. Its session and mailbox survive — reconnecting recovers
     * everything. Dropping is deliberate: blocking would let one slow reader stall the
     * threads delivering to it, which is the failure the brief explicitly calls out.
     */
    SLOW_CONSUMER,

    // --- Stream-level faults (connection is closed) --------------------------
    /** Declared frame length was negative or above maxFrameBytes. */
    FRAME_TOO_LARGE,
    /** Length prefix was valid but the body was not a frame we understand. */
    MALFORMED_FRAME,

    // --- Lifecycle ----------------------------------------------------------
    /** Server is shutting down and will accept no further work. */
    SERVER_SHUTTING_DOWN
}
