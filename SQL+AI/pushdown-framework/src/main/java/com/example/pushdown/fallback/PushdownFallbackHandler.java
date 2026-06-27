package com.example.pushdown.fallback;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.spi.PushdownConnector;
import java.time.Duration;
import java.util.function.Function;

/**
 * Handles pushdown execution failures with retry + fallback to full scan.
 *
 * Fallback chain:
 * 1. Retry with backoff (max maxRetries)
 * 2. Retry exhausted → fallbackToFullScan + local filter
 * 3. Consecutive failures → circuit breaker opens → pushdown disabled for cool-down
 */
public class PushdownFallbackHandler {

    private final PushdownConnector connector;
    private final CircuitBreaker circuitBreaker;
    private final int maxRetries;
    private final Duration retryDelay;

    public PushdownFallbackHandler(PushdownConnector connector, int maxRetries, Duration retryDelay) {
        this.connector = connector;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
        this.circuitBreaker = new CircuitBreaker(5, Duration.ofSeconds(60));
    }

    /**
     * Check if pushdown is currently enabled for this source (circuit breaker).
     */
    public boolean isPushdownEnabled(String serverId) {
        return circuitBreaker.isClosed(serverId);
    }

    /**
     * Execute a pushdown operation with fallback.
     * On failure: retry, then fallback to full scan.
     *
     * @param serverId source identifier
     * @param operation the pushdown operation to execute
     * @param fallbackTable the table to fall back to (full scan)
     * @return result of operation, or empty if fallback was used
     */
    public <T> java.util.Optional<T> executeWithFallback(
            String serverId,
            Function<Void, java.util.Optional<T>> operation,
            TableHandle fallbackTable) {
        
        if (!circuitBreaker.isClosed(serverId)) {
            // Circuit open → skip pushdown entirely
            return java.util.Optional.empty();
        }

        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                java.util.Optional<T> result = operation.apply(null);
                if (result.isPresent()) {
                    circuitBreaker.recordSuccess(serverId);
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelay.toMillis() * (attempt + 1)); // backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // All retries exhausted → record failure + fallback
        circuitBreaker.recordFailure(serverId);
        
        // Fallback: caller should use fallbackTable for full scan
        // We return empty to signal "no pushdown, use local execution"
        return java.util.Optional.empty();
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
