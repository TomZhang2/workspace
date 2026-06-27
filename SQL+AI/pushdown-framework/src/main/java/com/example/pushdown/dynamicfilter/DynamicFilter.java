package com.example.pushdown.dynamicfilter;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.ColumnHandle;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Consumer-side dynamic filter interface (what a scan receives).
 * Lifecycle: PENDING → PARTIAL → FINAL | ERROR
 */
public interface DynamicFilter {
    CompletableFuture<?> isBlocked();
    Optional<ConnectorExpression> getCurrentPredicate();
    Set<ColumnHandle> getCoveredColumns();
    boolean isComplete();
    Optional<Throwable> getError();

    static DynamicFilter pending(Set<ColumnHandle> columns) {
        return new PendingDynamicFilter(columns);
    }

    static DynamicFilter partial(Set<ColumnHandle> columns, ConnectorExpression predicate,
                                   CompletableFuture<?> blockedFuture) {
        return new PartialDynamicFilter(columns, predicate, blockedFuture);
    }

    static DynamicFilter finalState(Set<ColumnHandle> columns, ConnectorExpression predicate) {
        return new FinalDynamicFilter(columns, predicate);
    }

    static DynamicFilter error(Set<ColumnHandle> columns, Throwable error) {
        return new ErrorDynamicFilter(columns, error);
    }

    record PendingDynamicFilter(Set<ColumnHandle> columns) implements DynamicFilter {
        @Override public CompletableFuture<?> isBlocked() { return new CompletableFuture<>(); }
        @Override public Optional<ConnectorExpression> getCurrentPredicate() { return Optional.empty(); }
        @Override public Set<ColumnHandle> getCoveredColumns() { return columns; }
        @Override public boolean isComplete() { return false; }
        @Override public Optional<Throwable> getError() { return Optional.empty(); }
    }

    record PartialDynamicFilter(Set<ColumnHandle> columns, ConnectorExpression predicate,
                                  CompletableFuture<?> blockedFuture) implements DynamicFilter {
        @Override public CompletableFuture<?> isBlocked() { return blockedFuture; }
        @Override public Optional<ConnectorExpression> getCurrentPredicate() { return Optional.of(predicate); }
        @Override public Set<ColumnHandle> getCoveredColumns() { return columns; }
        @Override public boolean isComplete() { return false; }
        @Override public Optional<Throwable> getError() { return Optional.empty(); }
    }

    record FinalDynamicFilter(Set<ColumnHandle> columns, ConnectorExpression predicate) implements DynamicFilter {
        @Override public CompletableFuture<?> isBlocked() { return CompletableFuture.completedFuture(null); }
        @Override public Optional<ConnectorExpression> getCurrentPredicate() { return Optional.of(predicate); }
        @Override public Set<ColumnHandle> getCoveredColumns() { return columns; }
        @Override public boolean isComplete() { return true; }
        @Override public Optional<Throwable> getError() { return Optional.empty(); }
    }

    record ErrorDynamicFilter(Set<ColumnHandle> columns, Throwable error) implements DynamicFilter {
        @Override public CompletableFuture<?> isBlocked() {
            CompletableFuture<?> f = new CompletableFuture<>();
            f.completeExceptionally(error);
            return f;
        }
        @Override public Optional<ConnectorExpression> getCurrentPredicate() { return Optional.empty(); }
        @Override public Set<ColumnHandle> getCoveredColumns() { return columns; }
        @Override public boolean isComplete() { return false; }
        @Override public Optional<Throwable> getError() { return Optional.of(error); }
    }
}
