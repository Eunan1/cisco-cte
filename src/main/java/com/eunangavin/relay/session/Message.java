package com.eunangavin.relay.session;

/**
 * One message in a recipient's mailbox.
 *
 * <p>Distinct from a {@code Frame}: a frame is any unit on the wire, a message is the
 * content one client sends another. Only {@code SEND} and {@code DELIVER} carry one.
 *
 * <p>The id is <b>client-supplied</b>, not server-generated. That is what lets a client
 * retry a send idempotently — the server echoes the id back, uses it as the ack key, and
 * rejects a second send bearing an id already live in the target mailbox.
 *
 * <p>A record, so it is immutable: messages cross thread boundaries constantly (a sender's
 * reader thread enqueues, a recipient's writer thread encodes) and immutable objects need
 * no synchronisation.
 */
public record Message(String id, String from, String payload) {
}
