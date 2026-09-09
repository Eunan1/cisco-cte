package com.eunangavin.relay.session;

import com.eunangavin.relay.protocol.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The delivery pump, tested with a fake connection instead of a socket.
 *
 * <p>This is where the <b>slow-consumer</b> path is covered. A socket-level version would
 * have to fill the OS send and receive buffers — roughly 64 KiB each — before the
 * application's outbound queue backed up at all, so it would depend on platform buffer
 * sizes and be flaky. A connection that simply refuses offers reproduces the same condition
 * exactly, on one thread, in microseconds.
 *
 * <p>(For the record: {@code DeliveryTest.slowRecipientDoesNotDelayUnrelatedClients} drops
 * its slow client via a socket write failure, not via this path. It proves isolation, which
 * is what it claims; it does not prove queue overflow handling. This does.)
 */
class ClientSessionTest {

    /** Accepts a fixed number of offers, then refuses everything — like a full queue. */
    private static final class FakeConnection implements ClientConnection {
        private final String label;
        private final List<Frame> accepted = new ArrayList<>();
        private int remainingCapacity;
        private ClientSession session;
        private String closedReason;

        FakeConnection(String label, int capacity) {
            this.label = label;
            this.remainingCapacity = capacity;
        }

        @Override public boolean offer(Frame frame) {
            if (remainingCapacity <= 0) {
                return false;
            }
            remainingCapacity--;
            accepted.add(frame);
            return true;
        }
        @Override public void close(String reason) {
            closedReason = reason;
        }
        @Override public ClientSession session() {
            return session;
        }
        @Override public void bind(ClientSession session) {
            this.session = session;
        }
        @Override public String label() {
            return label;
        }

        List<String> deliveredIds() {
            return accepted.stream()
                    .filter(f -> f instanceof Frame.Deliver)
                    .map(f -> ((Frame.Deliver) f).messageId())
                    .toList();
        }
    }

    private final ClientSession session = new ClientSession("bob", 100);

    private void enqueue(String... ids) {
        for (String id : ids) {
            assertEquals(Mailbox.EnqueueResult.ACCEPTED,
                    session.enqueue(new Message(id, "alice", "payload")));
        }
    }

    @Test
    @DisplayName("pump delivers everything pending, in order, to an attached connection")
    void pumpDeliversInOrder() {
        var bob = new FakeConnection("bob-conn", 10);
        session.attach(bob);
        enqueue("m1", "m2", "m3");

        assertNull(session.pump(), "nothing to close - the connection kept up");

        assertEquals(List.of("m1", "m2", "m3"), bob.deliveredIds());
        assertEquals(0, session.pendingCount());
        assertEquals(3, session.inflightCount(), "delivered is not acknowledged");
    }

    @Test
    @DisplayName("pump stops and hands back the connection when the queue refuses a frame")
    void pumpReturnsConnectionWhenQueueRefuses() {
        // Capacity 2, three messages: the third offer fails.
        var bob = new FakeConnection("bob-conn", 2);
        session.attach(bob);
        enqueue("m1", "m2", "m3");

        ClientConnection toClose = session.pump();

        // The connection is RETURNED rather than closed here. close() is graceful and waits
        // for the writer to drain; doing that under the session lock would let one slow
        // socket stall every send to this identity.
        assertSame(bob, toClose, "the caller closes it, outside the lock");
        assertEquals(List.of("m1", "m2"), bob.deliveredIds());

        // m3 was never delivered, so it must not be left inflight.
        assertEquals(1, session.pendingCount());
        assertEquals(2, session.inflightCount());
    }

    @Test
    @DisplayName("a refused message keeps its position for the next connection")
    void refusedMessageKeepsItsPosition() {
        var slow = new FakeConnection("slow", 2);
        session.attach(slow);
        enqueue("m1", "m2", "m3");
        session.pump();                 // m1, m2 delivered; m3 refused and back in pending

        // The slow connection is dropped and the client reconnects.
        session.detach(slow);
        var fresh = new FakeConnection("fresh", 10);
        session.attach(fresh);

        assertNull(session.pump());

        // m1 and m2 were never acknowledged, so they are redelivered - ahead of m3, which
        // was accepted after them. Acceptance order survives the drop intact.
        assertEquals(List.of("m1", "m2", "m3"), fresh.deliveredIds());
    }

}
