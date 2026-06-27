package com.example.pushdown.result;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.handle.TableHandle;
import java.util.List;
import java.util.Optional;

public interface FilterResult {
    TableHandle pushedTable();
    List<ConjunctPushdown> conjunctResults();

    default ConnectorExpression combinedResidual() {
        return conjunctResults().stream()
            .map(ConjunctPushdown::residualExpression)
            .reduce(Expressions::logicalAnd)
            .orElse(Expressions.TRUE());
    }

    default Optional<Object> estimatedStats() { return Optional.empty(); }
}
