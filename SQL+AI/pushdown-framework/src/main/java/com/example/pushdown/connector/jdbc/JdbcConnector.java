package com.example.pushdown.connector.jdbc;

import com.example.pushdown.aggregate.AggregateMode;
import com.example.pushdown.aggregate.AggregateResult;
import com.example.pushdown.aggregate.IntermediateAggregate;
import com.example.pushdown.aggregate.MergeFunction;
import com.example.pushdown.aggregate.MergeFunctions;
import com.example.pushdown.deparse.SortItem;
import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.expression.FunctionSignature;
import com.example.pushdown.expression.FunctionVolatility;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.ConjunctPushdown;
import com.example.pushdown.result.FilterResult;
import com.example.pushdown.result.FilterResults;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;
import com.example.pushdown.shippability.ShippabilityChecker;
import com.example.pushdown.shippability.ShippabilityRegistry;
import com.example.pushdown.shippability.StableFunctionPinner;
import com.example.pushdown.spi.ConnectorCapability;
import com.example.pushdown.spi.ConnectorVersion;
import com.example.pushdown.spi.PushdownConnector;
import com.example.pushdown.topn.Collation;
import com.example.pushdown.topn.LimitResult;
import com.example.pushdown.topn.TopNResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC connector with real SQL pushdown (EXACT mode).
 *
 * <p>Uses {@link ShippabilityChecker} to determine which conjuncts are
 * shippable to the source, {@link StableFunctionPinner} to pin STABLE
 * functions to the snapshot timestamp, and {@code DefaultSqlDeparser} (via
 * {@code PushedPlan}) for SQL generation.
 *
 * <p>Shippable conjuncts are pushed in {@link PushdownMode#EXACT} mode (the
 * source evaluates them precisely); non-shippable conjuncts fall back to
 * {@link PushdownMode#IN_MEMORY} (evaluated by the engine after the scan).
 *
 * <p>Phase 3: supports aggregate pushdown for the standard shippable
 * aggregates ({@code COUNT}, {@code SUM}, {@code MIN}, {@code MAX},
 * {@code AVG}) in {@link AggregateMode#COMPLETE} mode — the source computes
 * the final aggregate values. Non-shippable HAVING conjuncts are kept as a
 * residual that the engine evaluates after the source returns rows.
 */
public class JdbcConnector implements PushdownConnector {

    /**
     * Provisional snapshot used by {@link #isFilterPushable} which — per the
     * {@link PushdownConnector} contract — does not receive a real snapshot.
     * The planner guarantees that a concrete snapshot is supplied to
     * {@link #applyFilter} before any pushdown executes, so STABLE functions
     * are treated as pushable during this pre-check. The checker only inspects
     * whether the snapshot reference is non-null (it does not read its value),
     * so this sentinel safely models "a snapshot will be available".
     */
    private static final SnapshotContext PROVISIONAL_SNAPSHOT = new SnapshotContext(
        Instant.EPOCH, SnapshotContext.IsolationLevel.SNAPSHOT, "provisional-pre-check");

    private final String serverId;
    private final ShippabilityChecker shippabilityChecker = new ShippabilityChecker();
    private final ShippabilityRegistry shippabilityRegistry = new ShippabilityRegistry();
    private final StableFunctionPinner stablePinner = new StableFunctionPinner();

    /**
     * The collation MySQL uses for {@code VARCHAR} columns under the default
     * {@code utf8mb4_general_ci} server collation. Returned in
     * {@link TopNResult#sortCollation()} so callers can decide whether the
     * pushed ordering is trustworthy for their comparison semantics.
     */
    private static final Collation DEFAULT_MYSQL_COLLATION = Collation.of("utf8mb4_general_ci");

    public JdbcConnector(String serverId) {
        this.serverId = serverId;
    }

    @Override
    public ConnectorVersion getVersion() { return ConnectorVersion.V2; }

    @Override
    public Set<ConnectorCapability> capabilities(TableHandle table) {
        return Set.of(ConnectorCapability.FILTER_PUSHDOWN, ConnectorCapability.FALLBACK);
    }

    @Override
    public boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                      ConnectorExpression predicate) {
        // Pushable if at least one conjunct is shippable. STABLE functions are
        // considered shippable here because a real snapshot is supplied to
        // applyFilter before execution.
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(predicate);
        return conjuncts.stream()
            .anyMatch(c -> shippabilityChecker.isShippable(c, session, PROVISIONAL_SNAPSHOT));
    }

    @Override
    public Optional<FilterResult> applyFilter(ConnectorSession session, TableHandle table,
                                                ConnectorExpression predicate,
                                                SnapshotContext snapshot) {
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(predicate);

        // If no conjunct is shippable, return empty (planner shouldn't call us, but safety).
        boolean anyShippable = conjuncts.stream()
            .anyMatch(c -> shippabilityChecker.isShippable(c, session, snapshot));
        if (!anyShippable) {
            return Optional.empty();
        }

        List<ConjunctPushdown> results = new ArrayList<>();
        for (ConnectorExpression conjunct : conjuncts) {
            if (shippabilityChecker.isShippable(conjunct, session, snapshot)) {
                // Pin STABLE functions in the pushed expression to the snapshot.
                ConnectorExpression pushed = (snapshot != null)
                    ? stablePinner.pinStableFunctions(conjunct, snapshot)
                    : conjunct;
                results.add(FilterResults.conjunct(
                    conjunct,                   // original
                    Optional.of(pushed),        // pushed (with STABLE pinned)
                    Expressions.TRUE(),         // residual (EXACT = source handles it)
                    PushdownMode.EXACT
                ));
            } else {
                // Non-shippable conjunct -> IN_MEMORY (engine evaluates after scan).
                results.add(FilterResults.conjunct(
                    conjunct,                   // original
                    Optional.empty(),           // nothing pushed
                    conjunct,                   // residual = full original
                    PushdownMode.IN_MEMORY
                ));
            }
        }

        return Optional.of(FilterResults.of(table, results));
    }

    // ====== Aggregate pushdown ======

    @Override
    public boolean isAggregatePushable(ConnectorSession session, TableHandle table,
                                         List<FunctionSignature> aggregates,
                                         List<ColumnHandle> groupingKeys,
                                         ConnectorExpression having) {
        if (aggregates.isEmpty()) {
            return false; // nothing to push
        }
        // Every aggregate must be IMMUTABLE and present in the source's
        // builtin catalog (COUNT/SUM/MIN/MAX/AVG for MySQL). VOLATILE/STABLE
        // aggregates and UDAFs are not pushable.
        for (FunctionSignature agg : aggregates) {
            if (agg.volatility() != FunctionVolatility.IMMUTABLE) {
                return false;
            }
            if (!shippabilityRegistry.isShippable(agg, session.serverId())) {
                return false;
            }
            if (mergeFunctionFor(agg.name()) == null) {
                return false; // known builtin but no merge function mapped
            }
        }
        return true;
    }

    @Override
    public Optional<AggregateResult> applyAggregate(
            ConnectorSession session, TableHandle table,
            List<FunctionSignature> aggregates,
            List<ColumnHandle> groupingKeys,
            ConnectorExpression having) {
        if (!isAggregatePushable(session, table, aggregates, groupingKeys, having)) {
            return Optional.empty();
        }

        List<IntermediateAggregate> intermediates = new ArrayList<>();
        for (FunctionSignature agg : aggregates) {
            MergeFunction mergeFn = mergeFunctionFor(agg.name());
            String alias = agg.name().toLowerCase() + "_partial";
            // COMPLETE mode: the source returns the final value, so the
            // intermediate type is the aggregate's declared return type and
            // the merge function is the identity-ish combiner for that agg.
            intermediates.add(IntermediateAggregate.of(
                agg, agg.returnType(), mergeFn, alias));
        }

        // Split HAVING: shippable conjuncts are pushed to the source; the
        // rest are kept as a residual the engine evaluates over the source
        // rows.
        ConnectorExpression residualHaving = computeResidualHaving(session, having);

        return Optional.of(AggregateResult.of(
            table,
            AggregateMode.COMPLETE,
            intermediates,
            residualHaving,
            List.of() // no remaining aggregates — every agg was pushed
        ));
    }

    /**
     * Map a standard SQL aggregate name to its {@link MergeFunction}.
     * Returns {@code null} for aggregates the framework does not yet know
     * how to merge (treated as non-pushable by {@link #isAggregatePushable}).
     */
    private MergeFunction mergeFunctionFor(String name) {
        return switch (name.toUpperCase()) {
            case "COUNT" -> MergeFunctions.count();
            case "SUM" -> MergeFunctions.sum();
            case "MIN" -> MergeFunctions.min();
            case "MAX" -> MergeFunctions.max();
            case "AVG" -> MergeFunctions.avg();
            default -> null;
        };
    }

    /**
     * Split the HAVING predicate into conjuncts; keep non-shippable conjuncts
     * as the residual the engine must evaluate. Returns {@code TRUE} when
     * every conjunct was shippable (no residual).
     */
    private ConnectorExpression computeResidualHaving(ConnectorSession session,
                                                       ConnectorExpression having) {
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(having);
        List<ConnectorExpression> residual = new ArrayList<>();
        for (ConnectorExpression conjunct : conjuncts) {
            if (!shippabilityChecker.isShippable(conjunct, session, PROVISIONAL_SNAPSHOT)) {
                residual.add(conjunct);
            }
        }
        if (residual.isEmpty()) {
            return Expressions.TRUE();
        }
        if (residual.size() == 1) {
            return residual.get(0);
        }
        return Expressions.logicalAnd(residual.toArray(new ConnectorExpression[0]));
    }

    // ====== TopN / Limit pushdown ======
    //
    // MySQL supports ORDER BY + LIMIT natively, so TopN pushdown is always
    // available. The source guarantees both the row ordering and the limit
    // (it never returns more rows than requested). The ordering is reported
    // under the default MySQL collation (utf8mb4_general_ci); callers that
    // need a stricter collation (e.g. binary/C) must not trust the order.
    //
    // Plain LIMIT without ORDER BY is also pushable; MySQL guarantees the
    // bound but not which rows it returns.

    @Override
    public boolean isTopNPushable(ConnectorSession session, TableHandle table,
                                    long limit, List<SortItem> orderBy) {
        // MySQL supports ORDER BY + LIMIT for any positive limit. We require
        // at least one sort item — without ordering, this is a plain LIMIT
        // and should go through applyLimit instead.
        return limit > 0 && orderBy != null && !orderBy.isEmpty();
    }

    @Override
    public Optional<TopNResult> applyTopN(ConnectorSession session, TableHandle table,
                                            long limit, List<SortItem> orderBy) {
        if (!isTopNPushable(session, table, limit, orderBy)) {
            return Optional.empty();
        }
        return Optional.of(TopNResult.of(
            table,
            true, // orderGuaranteed — MySQL honors ORDER BY
            true, // limitGuaranteed — MySQL honors LIMIT
            Optional.of(DEFAULT_MYSQL_COLLATION)
        ));
    }

    @Override
    public boolean isLimitPushable(ConnectorSession session, TableHandle table, long limit) {
        return limit > 0;
    }

    @Override
    public Optional<LimitResult> applyLimit(ConnectorSession session, TableHandle table, long limit) {
        if (!isLimitPushable(session, table, limit)) {
            return Optional.empty();
        }
        return Optional.of(LimitResult.of(table, true));
    }

    @Override
    public TableHandle fallbackToFullScan(TableHandle pushedTable) {
        return pushedTable;
    }

    @Override
    public boolean supportsFallback() { return true; }
}
