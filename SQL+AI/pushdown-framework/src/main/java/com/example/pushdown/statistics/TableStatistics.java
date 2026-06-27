package com.example.pushdown.statistics;

import com.example.pushdown.handle.ColumnHandle;
import java.util.Map;

public record TableStatistics(
    long rowCount,
    Map<ColumnHandle, ColumnStatistics> columnStats
) {
    public static TableStatistics of(long rowCount, Map<ColumnHandle, ColumnStatistics> columnStats) {
        return new TableStatistics(rowCount, columnStats);
    }
}
