package com.eunangavin.relay.config;

import java.time.Duration;
import java.util.function.UnaryOperator;

/**
 * Every bound the service enforces, in one place.
 *
 * <p>The brief requires that "mailboxes, message sizes, active connections, and buffers
 * are bounded", and that the README documents how ports and timeouts are controlled.
 * Collecting them here means the README has exactly one thing to describe and the server
 * can print its effective configuration on boot.
 *
 * <p>A record because configuration is read-only after startup, and immutability means it
 * can be shared across every thread without a thought.
 */
public record RelayConfig(
        int port,
        int maxFrameBytes,
        int maxPayloadBytes,
        int maxMailboxMessages,
        int maxConnections,
        int outboundQueueCapacity,
        Duration shutdownTimeout) {

    /**
     * Validation runs in the compact constructor, so an invalid configuration cannot exist
     * at all — there is no window where a half-checked object is reachable.
     */
    public RelayConfig {
        // Port 0 is deliberately legal: it asks the OS for an ephemeral port, which is how
        // every test binds without risking a collision on a fixed port.
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in [0, 65535], got " + port);
        }
        requirePositive("maxFrameBytes", maxFrameBytes);
        requirePositive("maxPayloadBytes", maxPayloadBytes);
        requirePositive("maxMailboxMessages", maxMailboxMessages);
        requirePositive("maxConnections", maxConnections);
        requirePositive("outboundQueueCapacity", outboundQueueCapacity);

        // The JSON envelope (type, messageId, to, from) wraps the payload, so a payload
        // exactly at the frame limit could never actually be encoded. Catching that here
        // beats discovering it as a mysterious FRAME_TOO_LARGE on a legal-looking message.
        if (maxPayloadBytes >= maxFrameBytes) {
            throw new IllegalArgumentException(
                    "maxPayloadBytes (" + maxPayloadBytes + ") must be below maxFrameBytes ("
                            + maxFrameBytes + ") to leave room for envelope fields");
        }

        if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must be non-negative");
        }
    }

    public static RelayConfig defaults() {
        return new RelayConfig(
                9090,          // port
                64 * 1024,     // maxFrameBytes      - JSON body; the 4-byte prefix is extra
                32 * 1024,     // maxPayloadBytes    - user content inside a SEND
                1000,          // maxMailboxMessages - pending + inflight, per identity
                256,           // maxConnections
                256,           // outboundQueueCapacity - per connection
                Duration.ofSeconds(5));
    }

    /** Reads overrides from the process environment. */
    public static RelayConfig fromEnvironment() {
        return from(System::getenv);
    }

    /** Default host a client connects to. */
    public static final String DEFAULT_HOST = "localhost";

    /**
     * Which host a <b>client</b> should connect to.
     *
     * <p>Deliberately not a component of this record. The record holds the <em>server's</em>
     * bounds, and the server never needs a host — it binds {@code 0.0.0.0} so that anything
     * outside a container can reach it. Only the client needs a target, so adding it to the
     * record would mean every server test constructing a value it never uses.
     *
     * <p>Needed by the Docker demo in STORY-6, where the client reaches the server by
     * container name rather than localhost.
     */
    public static String clientHost() {
        return clientHost(System::getenv);
    }

    static String clientHost(UnaryOperator<String> lookup) {
        String raw = lookup.apply("RELAY_HOST");
        return raw == null || raw.isBlank() ? DEFAULT_HOST : raw.trim();
    }

    /**
     * The testable form. Java cannot set environment variables in-process, so taking the
     * lookup as a parameter is what makes override behaviour unit-testable at all;
     * {@link #fromEnvironment()} is the thin production binding.
     */
    public static RelayConfig from(UnaryOperator<String> lookup) {
        RelayConfig d = defaults();
        return new RelayConfig(
                intFrom(lookup, "RELAY_PORT", d.port()),
                intFrom(lookup, "RELAY_MAX_FRAME_BYTES", d.maxFrameBytes()),
                intFrom(lookup, "RELAY_MAX_PAYLOAD_BYTES", d.maxPayloadBytes()),
                intFrom(lookup, "RELAY_MAX_MAILBOX_MESSAGES", d.maxMailboxMessages()),
                intFrom(lookup, "RELAY_MAX_CONNECTIONS", d.maxConnections()),
                intFrom(lookup, "RELAY_OUTBOUND_QUEUE_CAPACITY", d.outboundQueueCapacity()),
                Duration.ofMillis(intFrom(lookup, "RELAY_SHUTDOWN_TIMEOUT_MS",
                        (int) d.shutdownTimeout().toMillis())));
    }

    private static int intFrom(UnaryOperator<String> lookup, String name, int fallback) {
        String raw = lookup.apply(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // Falls back rather than throwing, per the story's acceptance criteria.
            // Worth revisiting: a typo like RELAY_MAX_MAILBOX_MESSAGES=1oo would silently
            // run with the default, and for a value that guards a resource limit,
            // failing fast at startup is arguably the safer behaviour.
            return fallback;
        }
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
    }
}
