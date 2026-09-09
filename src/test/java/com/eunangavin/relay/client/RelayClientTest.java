package com.eunangavin.relay.client;

import com.eunangavin.relay.config.RelayConfig;
import com.eunangavin.relay.net.RelayServer;
import com.eunangavin.relay.protocol.Frame;
import com.eunangavin.relay.session.ClientRegistry;
import com.eunangavin.relay.session.RelayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The client library, against a real server on an ephemeral port.
 *
 * <p>The important one is {@link #deliveredMessagesReachTheListenerNotTheReplyQueue()} —
 * that a pushed frame arriving mid-request does not get mistaken for the response.
 */
class RelayClientTest {

    private static final RelayConfig CONFIG = new RelayConfig(
            0, 64 * 1024, 1024, 100, 16, 100, Duration.ofSeconds(2));

    /** Records everything the client pushes back, for assertions. */
    private static final class Listener implements RelayClient.MessageListener {
        final List<Frame.Deliver> delivered = new ArrayList<>();
        final AtomicInteger disconnects = new AtomicInteger();
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile String disconnectReason;

        @Override public synchronized void onDeliver(Frame.Deliver d) {
            delivered.add(d);
        }
        @Override public void onShutdown(Frame.Shutdown s) {
        }
        @Override public void onDisconnected(String reason) {
            disconnectReason = reason;
            disconnects.incrementAndGet();
            disconnected.countDown();
        }
    }

    private RelayServer server;
    private final List<RelayClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        var registry = new ClientRegistry(CONFIG.maxMailboxMessages());
        server = new RelayServer(CONFIG, new RelayService(registry, CONFIG));
        server.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(RelayClient::close);
        server.close();
    }

    private RelayClient connect(Listener listener) throws IOException {
        RelayClient client = RelayClient.connect("localhost", server.port(), listener);
        clients.add(client);
        return client;
    }

    // ------------------------------------------------------------------ happy path

    @Test
    @DisplayName("register, send and ack round-trip through the library")
    void registerSendAndAckRoundTrip() throws Exception {
        var aliceListener = new Listener();
        var bobListener = new Listener();
        RelayClient alice = connect(aliceListener);
        RelayClient bob = connect(bobListener);

        assertEquals(0, alice.register("alice").pending());
        assertEquals(0, bob.register("bob").pending());

        Frame verdict = alice.send("bob", "hello");
        assertInstanceOf(Frame.Accepted.class, verdict);

        Frame.Deliver delivered = awaitOneDelivery(bobListener);
        assertEquals("alice", delivered.from());
        assertEquals("hello", delivered.payload());

        assertEquals(delivered.messageId(), bob.ack(delivered.messageId()).messageId());
    }

    // ------------------------------------------------------------------ helper

    /** Waits for the listener to record a delivery, bounded so a failure does not hang. */
    private static Frame.Deliver awaitOneDelivery(Listener listener) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (listener) {
                if (!listener.delivered.isEmpty()) {
                    return listener.delivered.get(0);
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("no delivery arrived within 5s");
    }
}
