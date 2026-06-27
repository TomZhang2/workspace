package com.example.pushdown.connector.mock;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.*;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.spi.*;
import java.util.*;

public class MockConnector implements PushdownConnector {

    @Override
    public ConnectorVersion getVersion() { return ConnectorVersion.V2; }

    @Override
    public Set<ConnectorCapability> capabilities(TableHandle table) {
        return Set.of(ConnectorCapability.FILTER_PUSHDOWN, ConnectorCapability.FALLBACK);
    }

    @Override
    public boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                      ConnectorExpression predicate) {
        return true;
    }

    @Override
    public Optional<FilterResult> applyFilter(ConnectorSession session, TableHandle table,
                                                ConnectorExpression predicate,
                                                SnapshotContext snapshot) {
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(predicate);
        List<ConjunctPushdown> results = conjuncts.stream()
            .map(c -> FilterResults.conjunct(c, Optional.empty(), c, PushdownMode.IN_MEMORY))
            .toList();
        return Optional.of(FilterResults.of(table, results));
    }

    @Override
    public TableHandle fallbackToFullScan(TableHandle pushedTable) {
        return pushedTable;
    }

    @Override
    public boolean supportsFallback() { return true; }
}
