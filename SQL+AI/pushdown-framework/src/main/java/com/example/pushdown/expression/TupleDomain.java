package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import java.util.Map;
import java.util.Optional;

public record TupleDomain(Map<ColumnHandle, Domain<?>> domains) implements ConnectorExpression {

    public static Optional<TupleDomain> asTupleDomain(ConnectorExpression expr) {
        return Optional.empty();
    }
}
