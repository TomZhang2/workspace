package com.example.pushdown.expression;

public sealed interface ConnectorExpression
    permits Variable, Constant, Call, Comparison, Logical, Cast, Special, TupleDomain {
}
