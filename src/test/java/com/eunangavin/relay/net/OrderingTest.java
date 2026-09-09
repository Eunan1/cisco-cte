package com.eunangavin.relay.net;

import com.eunangavin.relay.config.RelayConfig;
import com.eunangavin.relay.protocol.Frame;
import com.eunangavin.relay.session.ClientRegistry;
import com.eunangavin.relay.session.ClientSession;
import com.eunangavin.relay.session.RelayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ordering guarantee: <b>per-recipient FIFO, in server-acceptance order.</b>
 *
 * <p>Acceptance order is the moment the server enqueues a message and answers
 * {@code ACCEPTED}. That instant has a real, observable order because enqueueing happens
 * under the recipient's session lock — so two concurrent sends to the same recipient are
 * serialised by that lock, and whichever wins genuinely was first.
 *
 * <p>What is deliberately <b>not</b> promised, and therefore deliberately not asserted
 * anywhere below: any particular interleaving <em>between</em> senders. Two clients on
 * different machines share no clock, so there is no honest global order to claim — and a
 * test asserting one would be flaky by construction.
 */
class OrderingTest {

    /**
     * A dedicated config. Reusing {@code DeliveryTest}'s would break these tests
     * misleadingly: its tiny mailbox and outbound queue exist to make <em>drops</em> easy,
     * which is the opposite of what ordering needs.
     */
    private static final RelayConfig CONFIG = new RelayConfig(
            0,           // ephemeral port
            64 * 1024,   // maxFrameBytes
            1024,        // maxPayloadBytes
            500,         // maxMailboxMessages    - room for 100+ without hitting the bound
            16,          // maxConnections
            500,         // outboundQueueCapacity - room to avoid a slow-consumer drop
            Duration.ofSeconds(2));

    private static final Duration BOUND = Duration.ofSeconds(10);

    private ClientRegistry registry;
    private RelayServer server;
    private final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        registry = new ClientRegistry(CONFIG.maxMailboxMessages());
        server = new RelayServer(CONFIG, new RelayService(registry, CONFIG));
        server.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(TestClient::close);
        server.close();
    }

    private TestClient registered(String name) throws IOException {
        TestClient client = TestClient.connect(server.port());
        clients.add(client);
        client.send(new Frame.Register(name));
        client.expect(Frame.Registered.class);
        return client;
    }

    private ClientSession session(String name) {
        return registry.find(name).orElseThrow();
    }

    private static void awaitOffline(ClientSession session) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (session.isOnline()) {
                Thread.onSpinWait();
            }
        });
    }

    /** Collects exactly n DELIVER ids, in arrival order. */
    private static List<String> receive(TestClient client, int n) {
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(client.expect(Frame.Deliver.class).messageId());
        }
        return ids;
    }

    // ------------------------------------------------------------------ the base case

    @Test
    @DisplayName("100 sequential sends arrive in acceptance order")
    void sequentialSendsArriveInOrder() throws IOException {
        TestClient alice = registered("alice");
        TestClient bob = registered("bob");

        List<String> sent = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            String id = "m" + i;
            alice.send(new Frame.Send(id, "bob", "payload " + i));
            assertEquals(id, alice.expect(Frame.Accepted.class).messageId());
            sent.add(id);   // one sender, so send order IS acceptance order
        }

        assertEquals(sent, receive(bob, 100));
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("concurrent senders: every message arrives once, each sender's own in order")
    void concurrentSendersKeepTheirOwnOrder() throws Exception {
        TestClient bob = registered("bob");
        TestClient alice = registered("alice");
        TestClient carol = registered("carol");

        int each = 50;
        // Released together, so both senders genuinely contend for bob's session lock.
        // Without this they would trivially serialise and the test would prove nothing.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);

        Thread aliceThread = Thread.ofVirtual().start(sendAll(alice, "alice", each, start, finished));
        Thread carolThread = Thread.ofVirtual().start(sendAll(carol, "carol", each, start, finished));

        start.countDown();
        assertTrue(finished.await(BOUND.toSeconds(), TimeUnit.SECONDS), "senders did not finish");

        List<String> received = assertTimeoutPreemptively(BOUND, () -> receive(bob, each * 2));

        // 1. Exactly once - nothing lost, nothing duplicated.
        assertEquals(each * 2, new HashSet<>(received).size(), "every message arrives exactly once");

        // 2. Each sender's own messages keep that sender's order. This holds because one
        //    client's frames travel one TCP connection and are read by one reader thread.
        assertEquals(idsFor("alice", each), onlyFrom(received, "alice"));
        assertEquals(idsFor("carol", each), onlyFrom(received, "carol"));

        // 3. NOTHING is asserted about how the two interleave. That is not promised, and a
        //    test that assumed an interleaving would fail randomly.
        aliceThread.join();
        carolThread.join();
    }

    private static Runnable sendAll(TestClient client, String prefix, int count,
                                    CountDownLatch start, CountDownLatch finished) {
        return () -> {
            try {
                start.await();
                for (int i = 1; i <= count; i++) {
                    client.send(new Frame.Send(prefix + "-" + i, "bob", "payload"));
                    client.expect(Frame.Accepted.class);
                }
            } catch (Exception e) {
                throw new AssertionError(prefix + " failed to send", e);
            } finally {
                finished.countDown();
            }
        };
    }

    private static List<String> idsFor(String prefix, int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add(prefix + "-" + i);
        }
        return ids;
    }

    private static List<String> onlyFrom(List<String> received, String prefix) {
        return received.stream().filter(id -> id.startsWith(prefix + "-")).toList();
    }

    // ------------------------------------------------------------------ redelivery

    @Test
    @DisplayName("redelivered messages precede ones accepted while offline")
    void redeliveryPreservesAcceptanceOrder() throws IOException {
        // The test most likely to catch a regression. Requeueing to the TAIL instead of the
        // head would still deliver everything, so every happy-path test would pass while
        // the ordering guarantee was silently false.
        TestClient alice = registered("alice");
        TestClient bob = registered("bob");

        for (String id : List.of("m1", "m2", "m3")) {
            alice.send(new Frame.Send(id, "bob", "payload"));
            alice.expect(Frame.Accepted.class);
        }
        assertEquals(List.of("m1", "m2", "m3"), receive(bob, 3));

        bob.send(new Frame.Ack("m2"));            // out-of-order ack, which is permitted
        bob.expect(Frame.AckOk.class);

        bob.close();
        awaitOffline(session("bob"));

        alice.send(new Frame.Send("m4", "bob", "accepted while offline"));
        alice.expect(Frame.Accepted.class);

        TestClient bobAgain = TestClient.connect(server.port());
        clients.add(bobAgain);
        bobAgain.send(new Frame.Register("bob"));
        assertEquals(3, bobAgain.expect(Frame.Registered.class).pending());

        // m1 and m3 were accepted before m4 and were never acknowledged, so they come first
        // and in their original relative order. Requeue-to-tail would give m4, m1, m3.
        assertEquals(List.of("m1", "m3", "m4"), receive(bobAgain, 3));
    }

}
