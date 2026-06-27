package com.example.pushdown.shippability;

import com.example.pushdown.expression.*;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;

/**
 * Recursively determines whether an entire {@link ConnectorExpression} is
 * shippable to the source identified by {@link ConnectorSession#serverId()}.
 *
 * <p>An expression is shippable when:
 * <ul>
 *   <li>{@link Variable} / {@link Constant} / {@link TupleDomain}: always shippable.</li>
 *   <li>{@link Call}: the function is not {@link FunctionVolatility#VOLATILE},
 *       STABLE functions require a non-null {@link SnapshotContext} for pinning,
 *       the function passes the {@link ShippabilityRegistry} whitelist, and all
 *       arguments are themselves shippable.</li>
 *   <li>{@link Comparison} / {@link Logical} / {@link Cast} / {@link Special}:
 *       shippable iff all sub-expressions are shippable.</li>
 * </ul>
 *
 * <p>Unknown expression types fall back to {@code false} (safe default).
 */
public class ShippabilityChecker {

    private final ShippabilityRegistry registry = new ShippabilityRegistry();

    public boolean isShippable(ConnectorExpression expr, ConnectorSession session, SnapshotContext snapshot) {
        if (expr instanceof Variable v) {
            return true; // Column references are always in-source (checked at higher level)
        }
        if (expr instanceof Constant) {
            return true; // Literals are always shippable
        }
        if (expr instanceof Call call) {
            FunctionSignature sig = call.function();
            // VOLATILE → never shippable
            if (sig.volatility() == FunctionVolatility.VOLATILE) {
                return false;
            }
            // STABLE → needs snapshot pinning (Phase 2: check snapshot present)
            if (sig.volatility() == FunctionVolatility.STABLE) {
                if (snapshot == null) {
                    return false; // Can't pin without snapshot
                }
            }
            // Check builtin/extension whitelist
            if (!registry.isShippable(sig, session.serverId())) {
                return false;
            }
            // All args must be shippable
            return call.args().stream().allMatch(a -> isShippable(a, session, snapshot));
        }
        if (expr instanceof Comparison cmp) {
            return isShippable(cmp.left(), session, snapshot)
                && isShippable(cmp.right(), session, snapshot);
        }
        if (expr instanceof Logical log) {
            return log.terms().stream().allMatch(t -> isShippable(t, session, snapshot));
        }
        if (expr instanceof Cast cast) {
            return isShippable(cast.expr(), session, snapshot);
        }
        if (expr instanceof Special special) {
            if (!isShippable(special.expr(), session, snapshot)) return false;
            return special.args().stream().allMatch(a -> isShippable(a, session, snapshot));
        }
        if (expr instanceof TupleDomain) {
            return true; // TupleDomain is column-domain only, always shippable
        }
        return false; // Unknown expression type → not shippable (safe default)
    }
}
