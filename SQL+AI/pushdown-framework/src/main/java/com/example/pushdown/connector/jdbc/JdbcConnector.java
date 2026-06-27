package com.example.pushdown.connector.jdbc;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.ConjunctPushdown;
import com.example.pushdown.result.FilterResult;
import com.example.pushdown.result.FilterResults;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;
import com.example.pushdown.shippability.ShippabilityChecker;
import com.example.pushdown.shippability.StableFunctionPinner;
import com.example.pushdown.spi.ConnectorCapability;
import com.example.pushdown.spi.ConnectorVersion;
import com.example.pushdown.spi.PushdownConnector;
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
    private final StableFunctionPinner stablePinner = new StableFunctionPinner();

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

    @Override
    public TableHandle fallbackToFullScan(TableHandle pushedTable) {
        return pushedTable;
    }

    @Override
    public boolean supportsFallback() { return true; }
}
