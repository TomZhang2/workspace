package com.example.pushdown.expression;

import java.util.List;

public record Special(SpecialKind kind, ConnectorExpression expr, List<ConnectorExpression> args)
    implements ConnectorExpression {}
