package com.eunangavin.relay.session;

import com.eunangavin.relay.protocol.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reattachment and takeover are pure logic, so they get pure tests — no sockets, no
 * threads, no timing. That is exactly what the {@link ClientConnection} interface was
 * introduced for: {@code session} never imports {@code java.net}, so a fake is eight lines.
 */
class ClientRegistryTest {

    /** Records what it was sent and whether it was closed. Nothing else. */
    private static final class FakeConnection implements ClientConnection {
        private final String label;
        private final List<Frame> received = new ArrayList<>();
        private ClientSession session;
        private boolean closed;

        FakeConnection(String label) {
            this.label = label;
        }

        @Override public boolean offer(Frame frame) {
            received.add(frame);
            return true;
        }
        @Override public void close(String reason) {
            closed = true;
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
    }

    private final ClientRegistry registry = new ClientRegistry(100);

    @Test
    @DisplayName("registering an unknown name creates and binds a session")
    void registerCreatesSession() {
        var alice = new FakeConnection("alice-conn");

        var result = registry.register("alice", alice);

        assertEquals("alice", result.session().clientId());
        assertNull(result.evicted(), "nothing to evict on a first registration");
        assertSame(result.session(), alice.session(), "connection must be bound to its session");
        assertTrue(result.session().isOnline());
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("an evicted connection's late disconnect does not detach its successor")
    void evictedConnectionDisconnectDoesNotDetachSuccessor() {
        // The race that makes reconnect flaky if you get it wrong:
        //   t0  A registers as alice
        //   t1  B registers as alice, A is evicted
        //   t2  A's reader loop finally exits and calls disconnect  <-- must not kill B
        var a = new FakeConnection("A");
        var b = new FakeConnection("B");
        registry.register("alice", a);
        ClientSession session = registry.register("alice", b).session();

        registry.disconnect(a);

        assertTrue(session.isOnline(), "B must still be attached");
        assertSame(b, session.connection());
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "   ", "has space", "semi;colon", "sla/sh", "unicode-é"})
    @DisplayName("invalid client ids are rejected")
    void rejectsInvalidClientIds(String clientId) {
        // Client ids become map keys and appear in logs, so they are validated, not trusted.
        assertFalse(ClientRegistry.isValidClientId(clientId));
    }

}
