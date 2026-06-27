package com.example.pushdown.dynamicfilter;

import com.example.pushdown.handle.ColumnHandle;
import java.util.Set;

/**
 * Producer-side interface: collects build-side values and produces snapshots.
 */
public interface DynamicFilterSource {
    int maxDistinctValues();
    OverflowStrategy overflowStrategy();
    void addValues(Set<Object> values);
    void markFinal();
    void markError(Throwable cause);
    DynamicFilterSnapshot snapshot();
}
