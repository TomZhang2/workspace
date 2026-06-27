package com.example.pushdown.fallback;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Circuit breaker: tracks per-source failures and temporarily disables pushdown
 * when a source is consistently failing.
 *
 * States: CLOSED (normal) → OPEN (failures exceeded threshold) → HALF_OPEN (cool-down elapsed)
 */
public class CircuitBreaker {

    private final int failureThreshold;
    private final Duration coolDown;
    private final ConcurrentHashMap<String, SourceState> sourceStates = new ConcurrentHashMap<>();

    public CircuitBreaker(int failureThreshold, Duration coolDown) {
        this.failureThreshold = failureThreshold;
        this.coolDown = coolDown;
    }

    /**
     * Check if pushdown is enabled for a source.
     * CLOSED → pushdown enabled
     * OPEN → pushdown disabled (until cool-down elapses → HALF_OPEN)
     * HALF_OPEN → pushdown enabled (probing)
     */
    public boolean isClosed(String serverId) {
        SourceState state = sourceStates.get(serverId);
        if (state == null) return true; // no failures → closed

        if (state.openedAt != null) {
            if (Instant.now().isAfter(state.openedAt.plus(coolDown))) {
                // Cool-down elapsed → half-open (allow one probe)
                return true;
            }
            return false; // still open
        }
        return true;
    }

    public void recordSuccess(String serverId) {
        SourceState state = sourceStates.get(serverId);
        if (state != null) {
            // Reset on success (half-open probe succeeded)
            sourceStates.remove(serverId);
        }
    }

    public void recordFailure(String serverId) {
        sourceStates.compute(serverId, (k, state) -> {
            if (state == null) {
                state = new SourceState(new AtomicInteger(0), null);
            }
            int count = state.failures.incrementAndGet();
            if (count >= failureThreshold) {
                return new SourceState(state.failures, Instant.now()); // open
            }
            return state;
        });
    }

    public void reset(String serverId) {
        sourceStates.remove(serverId);
    }

    private record SourceState(AtomicInteger failures, Instant openedAt) {}
}
