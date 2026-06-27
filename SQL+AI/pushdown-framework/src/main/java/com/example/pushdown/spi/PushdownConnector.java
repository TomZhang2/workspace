package com.example.pushdown.spi;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.result.FilterResult;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;
import java.util.Optional;
import java.util.Set;

public interface PushdownConnector {

    ConnectorVersion getVersion();
    Set<ConnectorCapability> capabilities(TableHandle table);

    default boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                      ConnectorExpression predicate) {
        return false;
    }

    default Optional<FilterResult> applyFilter(ConnectorSession session, TableHandle table,
                                                ConnectorExpression predicate,
                                                SnapshotContext snapshot) {
        return Optional.empty();
    }

    default boolean supportsDynamicFilter(TableHandle table, ColumnHandle column) {
        return false;
    }

    default Optional<Object> getTableStatistics(ConnectorSession session, TableHandle table) {
        return Optional.empty();
    }

    default TableHandle fallbackToFullScan(TableHandle pushedTable) {
        throw new UnsupportedOperationException("This connector does not support fallback");
    }

    default boolean supportsFallback() { return true; }
}
