package com.example.pushdown.expression;

public record Comparison(Operator op, ConnectorExpression left, ConnectorExpression right)
    implements ConnectorExpression {}
