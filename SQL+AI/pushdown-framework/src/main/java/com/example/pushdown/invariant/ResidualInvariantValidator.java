package com.example.pushdown.invariant;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.ConjunctPushdown;
import java.util.List;

public class ResidualInvariantValidator {

    public void validateFilter(ConnectorExpression originalPredicate,
                                List<ConjunctPushdown> conjunctResults) {
        for (ConjunctPushdown cp : conjunctResults) {
            switch (cp.mode()) {
                case EXACT -> {
                    assert cp.pushedExpression().isPresent()
                        : "EXACT conjunct must have pushed expression";
                }
                case CONSERVATIVE -> {
                    assert expressionsEqual(cp.residualExpression(), cp.originalConjunct())
                        : "CONSERVATIVE residual must equal original conjunct";
                }
                case IN_MEMORY -> {
                    assert cp.pushedExpression().isEmpty()
                        : "IN_MEMORY conjunct must have empty pushed expression";
                    assert expressionsEqual(cp.residualExpression(), cp.originalConjunct())
                        : "IN_MEMORY residual must equal original conjunct";
                }
            }
        }
    }

    private boolean expressionsEqual(ConnectorExpression a, ConnectorExpression b) {
        return a.equals(b);
    }
}
