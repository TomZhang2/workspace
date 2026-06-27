package com.example.pushdown.shippability;

import com.example.pushdown.expression.*;
import com.example.pushdown.session.SnapshotContext;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Replaces {@link FunctionVolatility#STABLE} function calls (e.g. {@code now()})
 * with a pinned timestamp {@link Constant} drawn from {@link SnapshotContext#queryTimestamp()}.
 *
 * <p>Pinning ensures that every STABLE function in a query resolves against the
 * exact same instant, guaranteeing snapshot consistency when the resulting
 * expression is pushed to the source.
 *
 * <p>The transformation is structural and recursive: STABLE calls become
 * constants; all other expression nodes are rebuilt with their (possibly
 * pinned) children, preserving immutability of the IR.
 */
public class StableFunctionPinner {

    public ConnectorExpression pinStableFunctions(ConnectorExpression expr, SnapshotContext snapshot) {
        if (expr instanceof Call call) {
            if (call.function().volatility() == FunctionVolatility.STABLE) {
                // Replace with pinned timestamp constant
                Instant pinned = snapshot.queryTimestamp();
                return new Constant(pinned, call.type());
            }
            // Recurse into args (immutable function — pin any stable args)
            List<ConnectorExpression> pinnedArgs = call.args().stream()
                .map(a -> pinStableFunctions(a, snapshot))
                .collect(Collectors.toList());
            return new Call(call.function(), pinnedArgs, call.type());
        }
        if (expr instanceof Comparison cmp) {
            return new Comparison(cmp.op(),
                pinStableFunctions(cmp.left(), snapshot),
                pinStableFunctions(cmp.right(), snapshot));
        }
        if (expr instanceof Logical log) {
            List<ConnectorExpression> pinnedTerms = log.terms().stream()
                .map(t -> pinStableFunctions(t, snapshot))
                .collect(Collectors.toList());
            return new Logical(log.op(), pinnedTerms);
        }
        if (expr instanceof Cast cast) {
            return new Cast(pinStableFunctions(cast.expr(), snapshot), cast.targetType());
        }
        if (expr instanceof Special special) {
            ConnectorExpression pinnedExpr = pinStableFunctions(special.expr(), snapshot);
            List<ConnectorExpression> pinnedArgs = special.args().stream()
                .map(a -> pinStableFunctions(a, snapshot))
                .collect(Collectors.toList());
            return new Special(special.kind(), pinnedExpr, pinnedArgs);
        }
        // Variable, Constant, TupleDomain — no stable functions to pin
        return expr;
    }
}
