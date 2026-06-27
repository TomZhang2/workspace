package com.example.pushdown.expression;

import com.example.pushdown.type.Type;
import java.util.List;

/**
 * Function call: UPPER(name), date_trunc('month', ts), abs(x)
 * Volatility accessed via f.function().volatility() (v2.1 fix).
 */
public record Call(FunctionSignature function, List<ConnectorExpression> args, Type type)
    implements ConnectorExpression {}
