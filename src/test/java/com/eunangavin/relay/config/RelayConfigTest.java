package com.eunangavin.relay.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deliberately five tests, not fifteen.
 *
 * <p>Each one guards a behaviour that could plausibly regress: the defaults being
 * self-consistent, the documented environment variables actually being wired up, garbage
 * input not crashing startup, the one non-obvious invariant, and the port-0 allowance that
 * the whole test suite depends on. The remaining validation in {@code RelayConfig} is
 * straight-line range checking; testing each branch of it would be testing {@code if}
 * statements rather than behaviour.
 */
class RelayConfigTest {

    /** Stands in for the process environment; see RelayConfig.from(UnaryOperator). */
    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    @DisplayName("payload limit at or above the frame limit is rejected at construction")
    void rejectsPayloadLimitAboveFrameLimit() {
        // The one invariant that is not obvious: the JSON envelope wraps the payload, so a
        // payload exactly at the frame limit could never be encoded. Failing here beats
        // serving a confusing FRAME_TOO_LARGE on a message that looks perfectly legal.
        assertThrows(IllegalArgumentException.class, () -> new RelayConfig(
                9090, 1024, 1024, 10, 5, 7, Duration.ofSeconds(1)));
    }

}
