package com.example.pushdown.expression;

import com.example.pushdown.type.Type;

public record Cast(ConnectorExpression expr, Type targetType) implements ConnectorExpression {}
