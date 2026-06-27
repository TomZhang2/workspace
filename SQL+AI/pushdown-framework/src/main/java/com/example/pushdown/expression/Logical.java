package com.example.pushdown.expression;

import java.util.List;

public record Logical(LogicalOperator op, List<ConnectorExpression> terms)
    implements ConnectorExpression {}
