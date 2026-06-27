package com.example.pushdown.expression;

import com.example.pushdown.type.Type;
import java.util.ArrayList;
import java.util.List;

public final class Expressions {

    private Expressions() {}

    public static ConnectorExpression TRUE() {
        return new Constant(true, Type.BOOLEAN);
    }

    public static ConnectorExpression logicalAnd(ConnectorExpression... expressions) {
        if (expressions.length == 0) {
            return TRUE();
        }
        List<ConnectorExpression> terms = new ArrayList<>();
        for (ConnectorExpression expression : expressions) {
            // Simplify away TRUE constants: TRUE ∧ x == x
            if (!isTrueConstant(expression)) {
                terms.add(expression);
            }
        }
        if (terms.isEmpty()) {
            return TRUE();
        }
        if (terms.size() == 1) {
            return terms.get(0);
        }
        return new Logical(LogicalOperator.AND, List.copyOf(terms));
    }

    public static ConnectorExpression logicalAnd(ConnectorExpression a, ConnectorExpression b) {
        return logicalAnd(new ConnectorExpression[]{a, b});
    }

    public static List<ConnectorExpression> splitConjuncts(ConnectorExpression expr) {
        if (expr instanceof Logical logical && logical.op() == LogicalOperator.AND) {
            return new ArrayList<>(logical.terms());
        }
        return List.of(expr);
    }

    private static boolean isTrueConstant(ConnectorExpression expression) {
        return expression instanceof Constant constant
            && constant.type() == Type.BOOLEAN
            && Boolean.TRUE.equals(constant.value());
    }
}
