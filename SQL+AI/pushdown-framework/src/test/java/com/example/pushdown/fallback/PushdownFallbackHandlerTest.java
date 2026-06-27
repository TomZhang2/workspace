package com.example.pushdown.fallback;

import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.spi.PushdownConnector;
import com.example.pushdown.spi.ConnectorVersion;
import com.example.pushdown.spi.ConnectorCapability;
import com.example.pushdown.handle.TableHandle;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PushdownFallbackHandlerTest {

    @Test
    void successfulOperationReturnsResult() {
        PushdownConnector connector = new NoOpConnector();
        PushdownFallbackHandler handler = new PushdownFallbackHandler(connector, 3, Duration.ofMillis(10));
        
        Optional<String> result = handler.executeWithFallback("mysql-prod",
            v -> Optional.of("pushed_result"), new MockTableHandle("users"));
        
        assertThat(result).contains("pushed_result");
    }

    @Test
    void retryOnFailureThenSucceeds() {
        PushdownConnector connector = new NoOpConnector();
        PushdownFallbackHandler handler = new PushdownFallbackHandler(connector, 3, Duration.ofMillis(10));
        
        AtomicInteger attempts = new AtomicInteger(0);
        Optional<String> result = handler.executeWithFallback("mysql-prod",
            v -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new RuntimeException("source unavailable");
                }
                return Optional.of("success_on_retry");
            },
            new MockTableHandle("users"));
        
        assertThat(result).contains("success_on_retry");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void allRetriesExhaustedReturnsEmptyAndRecordsFailure() {
        PushdownConnector connector = new NoOpConnector();
        PushdownFallbackHandler handler = new PushdownFallbackHandler(connector, 2, Duration.ofMillis(10));
        
        Optional<String> result = handler.executeWithFallback("mysql-prod",
            v -> { throw new RuntimeException("source down"); },
            new MockTableHandle("users"));
        
        assertThat(result).isEmpty();
        // Circuit breaker should have recorded failure
        // After 1 failure, circuit still closed (threshold=5)
        assertThat(handler.isPushdownEnabled("mysql-prod")).isTrue();
    }

    @Test
    void circuitBreakerOpensAfterThresholdFailures() {
        PushdownConnector connector = new NoOpConnector();
        PushdownFallbackHandler handler = new PushdownFallbackHandler(connector, 0, Duration.ofMillis(1));
        
        // Trigger 5 failures to open the circuit (threshold=5)
        for (int i = 0; i < 5; i++) {
            handler.executeWithFallback("mysql-prod",
                v -> { throw new RuntimeException("source down"); },
                new MockTableHandle("users"));
        }
        
        // Circuit should now be open
        assertThat(handler.isPushdownEnabled("mysql-prod")).isFalse();
    }

    @Test
    void circuitBreakerSkipsPushdownWhenOpen() {
        PushdownConnector connector = new NoOpConnector();
        PushdownFallbackHandler handler = new PushdownFallbackHandler(connector, 0, Duration.ofMillis(1));
        
        // Open the circuit
        for (int i = 0; i < 5; i++) {
            handler.executeWithFallback("mysql-prod",
                v -> { throw new RuntimeException("down"); }, new MockTableHandle("users"));
        }
        
        // Now even a valid operation should be skipped (circuit open)
        AtomicInteger callCount = new AtomicInteger(0);
        Optional<String> result = handler.executeWithFallback("mysql-prod",
            v -> { callCount.incrementAndGet(); return Optional.of("result"); },
            new MockTableHandle("users"));
        
        assertThat(result).isEmpty();
        assertThat(callCount.get()).isEqualTo(0); // operation was NOT called
    }

    @Test
    void circuitBreakerResetsOnSuccess() {
        PushdownConnector connector = new NoOpConnector();
        PushdownFallbackHandler handler = new PushdownFallbackHandler(connector, 0, Duration.ofMillis(1));
        
        // Record 3 failures (below threshold)
        for (int i = 0; i < 3; i++) {
            handler.executeWithFallback("mysql-prod",
                v -> { throw new RuntimeException("down"); }, new MockTableHandle("users"));
        }
        
        // Now succeed → should reset
        handler.executeWithFallback("mysql-prod",
            v -> Optional.of("success"), new MockTableHandle("users"));
        
        // Circuit should still be closed (reset on success)
        assertThat(handler.isPushdownEnabled("mysql-prod")).isTrue();
    }

    static class NoOpConnector implements PushdownConnector {
        @Override public ConnectorVersion getVersion() { return ConnectorVersion.V2; }
        @Override public Set<ConnectorCapability> capabilities(TableHandle table) { return Set.of(); }
    }
}
