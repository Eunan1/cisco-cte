package com.eunangavin.relay.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * All known identities, keyed by client id.
 *
 * {@link ConcurrentHashMap} rather than a lock around a plain map: registrations from
 * different connections are independent and there is no reason to serialise them.
 * Per-identity coordination happens inside {@link ClientSession}, which holds its own lock, so
 * the map only ever needs to make lookup and creation safe.
 */
public final class ClientRegistry {

    /** Client ids become map keys and appear in logs, so they are validated, not trusted. */
    public static final int MAX_CLIENT_ID_LENGTH = 64;
    private static final Pattern VALID_CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

    /** Passed to every session it creates, so each mailbox carries the configured bound. */
    private final int maxMailboxMessages;

    public ClientRegistry(int maxMailboxMessages) {
        this.maxMailboxMessages = maxMailboxMessages;
    }

    /**
     * Outcome of a registration.
     *
     * @param session the session now bound to the incoming connection
     * @param evicted a connection displaced by takeover, or null. The caller closes it —
     *                deliberately not done here, so no socket I/O happens under a lock.
     */
    public record Registration(ClientSession session, ClientConnection evicted) {
    }

    public static boolean isValidClientId(String clientId) {
        return clientId != null
                && !clientId.isBlank()
                && clientId.length() <= MAX_CLIENT_ID_LENGTH
                && VALID_CLIENT_ID.matcher(clientId).matches();
    }

    /**
     * Claims an identity for a connection, creating the session if this is its first
     * appearance and reattaching to the existing one if it is not.
     *
     * Reattachment is the whole point: requirement 6 of the brief is satisfied by this
     * method returning the same {@link ClientSession} instance after a disconnect,
     * with its mailbox still intact.
     */
    public Registration register(String clientId, ClientConnection incoming) {
        if (!isValidClientId(clientId)) {
            throw new IllegalArgumentException("invalid clientId");
        }

        // The mapping function must stay trivial: computeIfAbsent holds a bin lock while it
        // runs, so anything slow or re-entrant in here would block unrelated registrations.
        ClientSession session = sessions.computeIfAbsent(
                clientId, id -> new ClientSession(id, maxMailboxMessages));

        // Bind before attach. If the incoming connection dies in between, its disconnect
        // finds session() already set and detaches correctly. The reverse order leaves a
        // window where a dying connection has no session reference and never detaches.
        incoming.bind(session);
        ClientConnection evicted = session.attach(incoming);

        return new Registration(session, evicted);
    }

    /** Detaches a connection from whatever identity it held. Safe if it never registered. */
    public void disconnect(ClientConnection leaving) {
        ClientSession session = leaving.session();
        if (session != null) {
            session.detach(leaving);
        }
    }

    public Optional<ClientSession> find(String clientId) {
        return Optional.ofNullable(sessions.get(clientId));
    }

    /**
     * Known identities, online or not.
     *
     * <p>Note this only grows. Registering many unique ids consumes memory without bound —
     * an accepted limitation for the timebox, documented in APPROACH.md, with idle-session
     * expiry as the named next step.
     */
    public int size() {
        return sessions.size();
    }
}
