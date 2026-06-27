package com.example.pushdown.dynamicfilter;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.ConjunctPushdown;
import java.util.List;
import java.util.Set;

/**
 * Combines static (plan-time) and dynamic (runtime) pushdown predicates.
 * v2.1 fix: mode-aware residual composition.
 *
 * - EXACT static: residual is TRUE (source handled it) → filtered out of engine residual
 * - CONSERVATIVE/IN_MEMORY static: residual = full original → included in engine residual
 * - Dynamic: when arrived, AND'd with static residual
 */
public record ScanContext(
    TableHandle table,
    List<ConjunctPushdown> staticConjuncts,
    DynamicFilter dynamicFilter,
    Set<ColumnHandle> dynamicFilterColumns
) {
    public static ScanContext of(TableHandle table, List<ConjunctPushdown> staticConjuncts,
                                   DynamicFilter dynamicFilter,
                                   Set<ColumnHandle> dynamicFilterColumns) {
        return new ScanContext(table, staticConjuncts, dynamicFilter, dynamicFilterColumns);
    }

    public ConnectorExpression computeEngineResidual(ConnectorExpression dynamicPredicate) {
        ConnectorExpression staticResidual = staticConjuncts.stream()
            .map(ConjunctPushdown::residualExpression)
            .filter(e -> !isTrueConstant(e))
            .reduce(Expressions::logicalAnd)
            .orElse(Expressions.TRUE());

        if (dynamicPredicate != null && !isTrueConstant(dynamicPredicate)) {
            return Expressions.logicalAnd(staticResidual, dynamicPredicate);
        }
        return staticResidual;
    }

    public boolean shouldReSkipOnDynamic() {
        return staticConjuncts.stream()
            .anyMatch(cp -> cp.mode() == PushdownMode.CONSERVATIVE);
    }

    private boolean isTrueConstant(ConnectorExpression e) {
        if (e instanceof Constant c) {
            return Boolean.TRUE.equals(c.value());
        }
        return false;
    }
}
