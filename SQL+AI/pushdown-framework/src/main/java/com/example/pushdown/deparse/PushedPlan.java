package com.example.pushdown.deparse;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.result.ConjunctPushdown;
import java.util.List;
import java.util.Optional;

public interface PushedPlan {
    TableHandle tableHandle();
    List<ConnectorExpression> projections();
    List<ConjunctPushdown> conjunctResults();
    Optional<List<ColumnHandle>> groupingKeys();
    Optional<ConnectorExpression> pushedHaving();
    Optional<List<SortItem>> sortItems();
    Optional<Long> limit();
    default int fetchSize() { return 100; }

    default ConnectorExpression residual() {
        return conjunctResults().stream()
            .map(ConjunctPushdown::residualExpression)
            .reduce(Expressions::logicalAnd)
            .orElse(Expressions.TRUE());
    }

    static Builder builder() { return new Builder(); }

    class Builder {
        private TableHandle tableHandle;
        private List<ConnectorExpression> projections = List.of();
        private List<ConjunctPushdown> conjunctResults = List.of();
        private Optional<List<ColumnHandle>> groupingKeys = Optional.empty();
        private Optional<ConnectorExpression> pushedHaving = Optional.empty();
        private Optional<List<SortItem>> sortItems = Optional.empty();
        private Optional<Long> limit = Optional.empty();
        private int fetchSize = 100;

        public Builder tableHandle(TableHandle t) { this.tableHandle = t; return this; }
        public Builder projections(List<ConnectorExpression> p) { this.projections = p; return this; }
        public Builder conjunctResults(List<ConjunctPushdown> c) { this.conjunctResults = c; return this; }
        public Builder groupingKeys(Optional<List<ColumnHandle>> g) { this.groupingKeys = g; return this; }
        public Builder pushedHaving(Optional<ConnectorExpression> h) { this.pushedHaving = h; return this; }
        public Builder sortItems(Optional<List<SortItem>> s) { this.sortItems = s; return this; }
        public Builder limit(Optional<Long> l) { this.limit = l; return this; }
        public Builder fetchSize(int f) { this.fetchSize = f; return this; }

        public PushedPlan build() {
            return new ImmutablePushedPlan(tableHandle, projections, conjunctResults,
                groupingKeys, pushedHaving, sortItems, limit, fetchSize);
        }
    }

    record ImmutablePushedPlan(
        TableHandle tableHandle,
        List<ConnectorExpression> projections,
        List<ConjunctPushdown> conjunctResults,
        Optional<List<ColumnHandle>> groupingKeys,
        Optional<ConnectorExpression> pushedHaving,
        Optional<List<SortItem>> sortItems,
        Optional<Long> limit,
        int fetchSize
    ) implements PushedPlan {}
}
