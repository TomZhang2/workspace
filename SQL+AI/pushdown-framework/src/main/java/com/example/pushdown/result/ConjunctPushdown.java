package com.example.pushdown.result;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.mode.PushdownMode;
import java.util.Optional;

public interface ConjunctPushdown {
    ConnectorExpression originalConjunct();
    Optional<ConnectorExpression> pushedExpression();
    ConnectorExpression residualExpression();
    PushdownMode mode();
}
