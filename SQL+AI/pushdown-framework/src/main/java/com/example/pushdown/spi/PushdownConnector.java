package com.example.pushdown.spi;

import com.example.pushdown.aggregate.AggregateResult;
import com.example.pushdown.deparse.SortItem;
import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.FunctionSignature;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.join.JoinResult;
import com.example.pushdown.join.JoinType;
import com.example.pushdown.result.FilterResult;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;
import com.example.pushdown.topn.LimitResult;
import com.example.pushdown.topn.TopNResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PushdownConnector {

    ConnectorVersion getVersion();
    Set<ConnectorCapability> capabilities(TableHandle table);

    default boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                      ConnectorExpression predicate) {
        return false;
    }

    default Optional<FilterResult> applyFilter(ConnectorSession session, TableHandle table,
                                                ConnectorExpression predicate,
                                                SnapshotContext snapshot) {
        return Optional.empty();
    }

    default boolean supportsDynamicFilter(TableHandle table, ColumnHandle column) {
        return false;
    }

    default Optional<Object> getTableStatistics(ConnectorSession session, TableHandle table) {
        return Optional.empty();
    }

    default TableHandle fallbackToFullScan(TableHandle pushedTable) {
        throw new UnsupportedOperationException("This connector does not support fallback");
    }

    default boolean supportsFallback() { return true; }

    // ====== Aggregate pushdown ======

    /**
     * Whether the source can push down the given aggregate functions over the
     * supplied grouping keys with the supplied HAVING predicate.
     *
     * <p>Default: {@code false} — aggregate pushdown is opt-in.
     *
     * @param aggregates   aggregate functions the engine wants to push
     * @param groupingKeys GROUP BY columns (may be empty for global aggregates)
     * @param having       HAVING predicate (use {@code TRUE} when none)
     */
    default boolean isAggregatePushable(ConnectorSession session, TableHandle table,
                                         List<FunctionSignature> aggregates,
                                         List<ColumnHandle> groupingKeys,
                                         ConnectorExpression having) {
        return false;
    }

    /**
     * Push the aggregate down to the source. Returns an {@link AggregateResult}
     * describing the pushed plan and any residual HAVING / aggregates the
     * engine must still evaluate, or {@code Optional.empty()} when the push
     * cannot be performed.
     */
    default Optional<AggregateResult> applyAggregate(
            ConnectorSession session, TableHandle table,
            List<FunctionSignature> aggregates,
            List<ColumnHandle> groupingKeys,
            ConnectorExpression having) {
        return Optional.empty();
    }

    // ====== Join pushdown ======
    //
    // Typed using JoinType and JoinResult. The connector decides whether the
    // join can be pushed wholesale (FULL_PUSH — both sides live on the same
    // source) or whether each side should be filtered independently before the
    // engine performs the join (FILTER_EACH_SIDE). Other cross-source
    // strategies (BROADCAST_IN_LIST, SEMI_JOIN) are described by
    // CrossSourceStrategy on the returned JoinResult.

    default boolean isJoinPushable(ConnectorSession session, JoinType joinType,
                                    TableHandle left, TableHandle right,
                                    ConnectorExpression condition) {
        return false;
    }

    default Optional<JoinResult> applyJoin(ConnectorSession session, JoinType joinType,
                                            TableHandle left, TableHandle right,
                                            ConnectorExpression condition) {
        return Optional.empty();
    }

    // ====== TopN pushdown ======
    //
    // The connector returns a TopNResult that records whether the source
    // guarantees the row ordering and the limit, plus the collation under
    // which the ordering is trustworthy. Callers consult
    // TopNResult#isOrderTrustworthy(Collation) before relying on the order.

    default boolean isTopNPushable(ConnectorSession session, TableHandle table,
                                    long limit, List<SortItem> orderBy) {
        return false;
    }

    default Optional<TopNResult> applyTopN(ConnectorSession session, TableHandle table,
                                            long limit, List<SortItem> orderBy) {
        return Optional.empty();
    }

    // ====== Limit pushdown ======
    //
    // Plain LIMIT without ORDER BY. The connector reports whether the source
    // guarantees the limit (some sources reserve the right to return fewer
    // rows; the engine must not assume a tight bound unless guaranteed).

    default boolean isLimitPushable(ConnectorSession session, TableHandle table, long limit) {
        return false;
    }

    default Optional<LimitResult> applyLimit(ConnectorSession session, TableHandle table, long limit) {
        return Optional.empty();
    }
}
