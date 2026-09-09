package com.eunangavin.relay.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.eunangavin.relay.session.Mailbox.EnqueueResult.ACCEPTED;
import static com.eunangavin.relay.session.Mailbox.EnqueueResult.MAILBOX_FULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests — no sockets, no threads, no timing.
 *
 * <p>This is the payoff for keeping the {@code session} package free of {@code java.net}:
 * the queueing rules that requirement 7 depends on can be tested exhaustively and
 * deterministically, without a single socket.
 */
class MailboxTest {

    private final Mailbox mailbox = new Mailbox(3);

    private static Message msg(String id) {
        return new Message(id, "alice", "payload-" + id);
    }

    /** Delivers everything currently pending and returns the ids, in order. */
    private List<String> drain() {
        List<String> delivered = new ArrayList<>();
        Message m;
        while ((m = mailbox.takeForDelivery()) != null) {
            delivered.add(m.id());
        }
        return delivered;
    }

    @Test
    @DisplayName("a message moves from pending to inflight when taken for delivery")
    void offerThenTakeForDeliveryMovesToInflight() {
        assertEquals(ACCEPTED, mailbox.offer(msg("m1")));
        assertEquals(1, mailbox.pendingCount());
        assertEquals(0, mailbox.inflightCount());

        Message taken = mailbox.takeForDelivery();

        assertEquals("m1", taken.id());
        assertEquals(0, mailbox.pendingCount());
        assertEquals(1, mailbox.inflightCount());
        assertEquals(1, mailbox.size(), "still retained - delivered is not acknowledged");

        assertNull(mailbox.takeForDelivery(), "nothing left pending");
    }

    @Test
    @DisplayName("ack removes only that message, and an unknown id is a harmless no-op")
    void ackRemovesOnlyThatMessage() {
        mailbox.offer(msg("m1"));
        mailbox.offer(msg("m2"));
        drain();

        assertTrue(mailbox.ack("m1"));
        assertEquals(1, mailbox.size());

        // A repeated ack, and an ack for an id this mailbox never held, are indistinguishable
        // here - and both are no-ops. At-least-once delivery guarantees repeats happen.
        assertFalse(mailbox.ack("m1"), "already acked");
        assertFalse(mailbox.ack("never-existed"));
        assertEquals(1, mailbox.size(), "neither no-op changed anything");
    }

    @Test
    @DisplayName("requeued inflight returns to the FRONT, in original order")
    void requeueInflightRestoresOriginalOrderAtTheFront() {
        // THREE messages minimum. With two, a reversed requeue would still put the right
        // message first half the time - this is the test that actually catches the bug of
        // walking inflight forwards while calling addFirst.
        mailbox.offer(msg("m1"));
        mailbox.offer(msg("m2"));
        mailbox.offer(msg("m3"));
        assertEquals(List.of("m1", "m2", "m3"), drain());

        mailbox.ack("m2");            // out-of-order ack: m1 and m3 remain inflight
        mailbox.requeueInflight();    // the connection went away

        assertEquals(0, mailbox.inflightCount());
        assertEquals(List.of("m1", "m3"), drain(), "original relative order preserved");
    }

    @Test
    @DisplayName("requeued messages go AHEAD of ones already pending")
    void requeuedMessagesGoAheadOfAlreadyPendingOnes() {
        // The only test in the project that distinguishes requeue-to-HEAD from
        // requeue-to-TAIL. Every other ordering test has an EMPTY pending deque at the
        // moment of requeue, where head and tail are the same place - so they all pass
        // against a to-the-tail implementation. Verified by mutation.
        //
        // Non-empty pending only happens when delivery stopped part-way, which in
        // production means the recipient's outbound queue refused a frame.
        mailbox.offer(msg("m1"));
        mailbox.offer(msg("m2"));
        mailbox.offer(msg("m3"));

        mailbox.takeForDelivery();   // m1 -> inflight
        mailbox.takeForDelivery();   // m2 -> inflight
        assertEquals(1, mailbox.pendingCount(), "m3 is still pending");

        mailbox.requeueInflight();

        // m1 and m2 were accepted before m3, so they must come back ahead of it.
        // To the tail would give [m3, m1, m2] - everything still delivered, order silently wrong.
        assertEquals(List.of("m1", "m2", "m3"), drain());
    }

    @Test
    @DisplayName("the bound counts pending plus inflight")
    void rejectsWhenFull() {
        assertEquals(ACCEPTED, mailbox.offer(msg("m1")));
        assertEquals(ACCEPTED, mailbox.offer(msg("m2")));
        assertEquals(ACCEPTED, mailbox.offer(msg("m3")));
        assertEquals(MAILBOX_FULL, mailbox.offer(msg("m4")));

        // Delivering does not free capacity: inflight is retained state, and a client that
        // never acks must not be able to grow the mailbox without limit.
        drain();
        assertEquals(3, mailbox.inflightCount());
        assertEquals(MAILBOX_FULL, mailbox.offer(msg("m4")));

        // Acking does free it.
        mailbox.ack("m1");
        assertEquals(ACCEPTED, mailbox.offer(msg("m4")));
    }

}
