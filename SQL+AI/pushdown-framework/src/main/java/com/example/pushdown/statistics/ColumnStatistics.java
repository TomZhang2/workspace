package com.example.pushdown.statistics;

import com.example.pushdown.type.Type;
import java.util.Optional;

public record ColumnStatistics(
    long distinctValuesCount,
    long nullCount,
    Optional<Object> minValue,
    Optional<Object> maxValue,
    Type type
) {
    public static ColumnStatistics of(long distinctValuesCount, long nullCount,
                                        Object min, Object max, Type type) {
        return new ColumnStatistics(distinctValuesCount, nullCount,
            Optional.ofNullable(min), Optional.ofNullable(max), type);
    }

    public static ColumnStatistics empty(Type type) {
        return new ColumnStatistics(0, 0, Optional.empty(), Optional.empty(), type);
    }
}
