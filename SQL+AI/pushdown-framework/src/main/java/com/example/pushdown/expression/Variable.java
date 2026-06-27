package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;

public record Variable(ColumnHandle column, Type type) implements ConnectorExpression {}
