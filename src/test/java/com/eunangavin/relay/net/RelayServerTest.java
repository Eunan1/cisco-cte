package com.eunangavin.relay.net;

import com.eunangavin.relay.config.RelayConfig;
import com.eunangavin.relay.protocol.ErrorCode;
import com.eunangavin.relay.protocol.Frame;
import com.eunangavin.relay.session.ClientRegistry;
import com.eunangavin.relay.session.ClientSession;
import com.eunangavin.relay.session.RelayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end lifecycle tests over real sockets on an ephemeral port.
 *
 * <p>Every test is bounded: {@link TestClient} sets {@code SO_TIMEOUT}, so a server that
 * never answers fails the test rather than hanging the build. No {@code Thread.sleep} is
 * used as synchronisation anywhere — waiting is always on a real signal with a deadline.
 */
class RelayServerTest {

    /** Small limits so the bounds can be exercised without building anything large. */
    private static final RelayConfig CONFIG = new RelayConfig(
            0,            // port 0 - the OS picks, so parallel runs never collide
            64 * 1024,    // maxFrameBytes
            32 * 1024,    // maxPayloadBytes
            100,          // maxMailboxMessages
            3,            // maxConnections - deliberately tiny
            8,            // outboundQueueCapacity - deliberately tiny
            Duration.ofSeconds(2));

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

    private TestClient client() throws IOException {
        TestClient client = TestClient.connect(server.port());
        clients.add(client);
        return client;
    }

    private TestClient registered(String name) throws IOException {
        TestClient client = client();
        client.send(new Frame.Register(name));
        assertEquals(name, client.expect(Frame.Registered.class).clientId());
        return client;
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    @DisplayName("binds an ephemeral port and reports the real one")
    void bindsEphemeralPortAndReportsIt() {
        assertNotEquals(0, server.port(), "port 0 must resolve to an OS-assigned port");
        assertTrue(server.port() > 0 && server.port() <= 65535);
    }

    @Test
    @DisplayName("two clients register independently and coexist")
    void twoClientsRegisterIndependently() throws IOException {
        registered("alice");
        registered("bob");

        assertEquals(2, registry.size());
        assertTrue(registry.find("alice").orElseThrow().isOnline());
        assertTrue(registry.find("bob").orElseThrow().isOnline());
    }

    // ------------------------------------------------------------------ identity

    @Test
    @DisplayName("reconnecting with the same id reattaches the same session")
    void reregisteringReattachesSameSession() throws IOException {
        // Requirement 6, over a real socket. The session object surviving is what makes its
        // mailbox — and so the offline messages in it — survive too.
        TestClient first = registered("alice");
        ClientSession original = registry.find("alice").orElseThrow();

        first.close();
        awaitOffline(original);

        registered("alice");

        assertSame(original, registry.find("alice").orElseThrow(),
                "reconnect must reattach, not create a new session");
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("a second connection takes over the identity and the first is closed")
    void newConnectionTakesOverIdentity() throws IOException {
        TestClient first = registered("alice");

        registered("alice");   // second connection, same name

        Frame notice = first.receive();
        assertEquals(ErrorCode.ALREADY_REGISTERED, ((Frame.Error) notice).code());
        first.expectClosed();
        assertTrue(registry.find("alice").orElseThrow().isOnline(), "successor still attached");
    }

    // ------------------------------------------------------------------ bounds

    @Test
    @DisplayName("the connection limit is enforced and existing clients are unaffected")
    void connectionLimitIsEnforced() throws IOException {
        for (int i = 0; i < CONFIG.maxConnections(); i++) {
            registered("client" + i);
        }

        try (TestClient overflow = TestClient.connect(server.port())) {
            assertEquals(ErrorCode.CONNECTION_LIMIT_REACHED,
                    overflow.expect(Frame.Error.class).code());
            overflow.expectClosed();
        }

        // The clients already connected must be untouched by someone else's rejection.
        clients.get(0).send(new Frame.Register("client0"));
        assertEquals(ErrorCode.ALREADY_REGISTERED, clients.get(0).expect(Frame.Error.class).code());
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("a malformed frame closes only that connection")
    void malformedFrameClosesOnlyThatConnection() throws IOException {
        TestClient healthy = registered("alice");
        TestClient hostile = client();

        // A length prefix that is valid, followed by a body that is not a frame at all.
        byte[] body = "definitely not a frame".getBytes();
        hostile.sendRaw(ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array());

        assertEquals(ErrorCode.MALFORMED_FRAME, hostile.expect(Frame.Error.class).code());
        hostile.expectClosed();

        // The other client keeps working - this is the isolation the brief asks for.
        healthy.send(new Frame.Register("alice"));
        assertEquals(ErrorCode.ALREADY_REGISTERED, healthy.expect(Frame.Error.class).code());
    }

    // ------------------------------------------------------------------ shutdown

    @Test
    @DisplayName("shutdown notifies clients, completes within the timeout, and frees the port")
    void shutdownCompletesWithinTimeout() throws IOException {
        TestClient client = registered("alice");
        int port = server.port();

        assertTimeoutPreemptively(CONFIG.shutdownTimeout().plusSeconds(2), () -> server.close());

        assertEquals("server shutting down", client.expect(Frame.Shutdown.class).reason());

        // The port must actually be released - otherwise a restart would fail.
        assertThrows(ConnectException.class, () -> new Socket("localhost", port).close());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Waits for a session to go offline.
     *
     * <p>Detach happens on the server's reader thread, which is not the test thread, so
     * there is a genuine handoff to wait for. Polling with a deadline rather than sleeping
     * a fixed duration keeps this fast when it succeeds and bounded when it does not.
     */
    private static void awaitOffline(ClientSession session) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (session.isOnline()) {
                Thread.onSpinWait();
            }
        });
    }
}
