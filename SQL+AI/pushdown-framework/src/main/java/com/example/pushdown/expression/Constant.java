package com.example.pushdown.expression;

import com.example.pushdown.type.Type;

public record Constant(Object value, Type type) implements ConnectorExpression {}
