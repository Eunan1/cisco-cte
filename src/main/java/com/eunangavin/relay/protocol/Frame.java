package com.eunangavin.relay.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A frame is one self-contained, length-delimited unit on the wire. It is a
 *
 * Jackson writes the {@code type} discriminator into the JSON and uses the
 * JsonSubTypes table below to pick the record when reading. Each entry sits beside
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
