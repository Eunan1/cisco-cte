package com.eunangavin.relay.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The entire protocol, on one screen.
 *
 * A frame is one self-contained, length-delimited unit on the wire. It is a
 * broader idea than a message: a message is the content Alice sends Bob, and only
 * Send and Deliver carry one.
 * REGISTER, ACK and ERROR are frames that are not messages.
 *
 * Sealed, with the records nested. sealed means the compiler knows the
 * complete set of implementations, so a {@code switch} over a {@code Frame} is exhaustive
 * with no {@code default} branch — add a frame type and every switch that fails to handle
 * it becomes a compile error rather than a runtime surprise.
 *
 * <p>There is no {@code permits} clause because Java infers it when every permitted
 * subtype is declared in the same source file. Keeping the records nested therefore buys
 * two things: one list to maintain instead of two, and a qualified name at the use site
 * ({@code Frame.Register}) that reads as "a Frame of kind Register". The cost is that
 * moving any record out to its own file will stop this interface compiling until an
 * explicit {@code permits} clause is added.
 *
 * <p>Records give immutability for free, which matters because frames cross thread
 * boundaries constantly — reader thread to session to another connection's writer thread —
 * and immutable objects need no synchronisation at all. They also give {@code equals},
 * which makes a round-trip test a one-line assertion.
 *
 * <p>Jackson writes the {@code type} discriminator into the JSON and uses the
 * {@link JsonSubTypes} table below to pick the record when reading. Each entry sits beside
 * the record it names, so adding an operation is one coherent edit in one place.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Frame.Register.class,   name = "REGISTER"),
        @JsonSubTypes.Type(value = Frame.Registered.class, name = "REGISTERED"),
        @JsonSubTypes.Type(value = Frame.Send.class,       name = "SEND"),
        @JsonSubTypes.Type(value = Frame.Accepted.class,   name = "ACCEPTED"),
        @JsonSubTypes.Type(value = Frame.Rejected.class,   name = "REJECTED"),
        @JsonSubTypes.Type(value = Frame.Deliver.class,    name = "DELIVER"),
        @JsonSubTypes.Type(value = Frame.Ack.class,        name = "ACK"),
        @JsonSubTypes.Type(value = Frame.AckOk.class,      name = "ACK_OK"),
        @JsonSubTypes.Type(value = Frame.Error.class,      name = "ERROR"),
        @JsonSubTypes.Type(value = Frame.Shutdown.class,   name = "SHUTDOWN")
})
public sealed interface Frame {

    // --- client -> server: the only three operations a client can invoke ---------------

    /** Claim an identity. Re-registering after a disconnect reattaches the same session. */
    record Register(String clientId) implements Frame {}

    /** Send an addressed message. The id is client-supplied so a retry can be idempotent. */
    record Send(String messageId, String to, String payload) implements Frame {}

    /** Confirm a delivered message. Only this removes it from the mailbox. */
    record Ack(String messageId) implements Frame {}

    // --- server -> client: responses ---------------------------------------------------

    /** {@code pending} tells a reconnecting client how much backlog is about to arrive. */
    record Registered(String clientId, int pending) implements Frame {}

    /** The message is in the recipient's mailbox. NOT a delivery receipt. */
    record Accepted(String messageId) implements Frame {}

    /** The message was not accepted; nothing was queued. */
    record Rejected(String messageId, ErrorCode code, String reason) implements Frame {}

    /** Ack processed. Returned even for stale or repeated acks, which are idempotent. */
    record AckOk(String messageId) implements Frame {}

    /** A fault not tied to a specific message. */
    record Error(ErrorCode code, String reason) implements Frame {}

    // --- server -> client: unsolicited pushes ------------------------------------------

    /** A message for you. It stays in the mailbox until you ACK it. */
    record Deliver(String messageId, String from, String payload) implements Frame {}

    /** The server is going away. Stop sending. */
    record Shutdown(String reason) implements Frame {}
}
