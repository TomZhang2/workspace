package com.example.pushdown.expression;

import com.example.pushdown.type.Type;
import java.util.List;

public record FunctionSignature(
    String name,
    List<Type> parameterTypes,
    Type returnType,
    FunctionVolatility volatility
) {}
