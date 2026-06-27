package com.example.pushdown.spi;

public enum ConnectorCapability {
    FILTER_PUSHDOWN,
    PROJECTION_PUSHDOWN,
    AGGREGATE_PUSHDOWN,
    JOIN_PUSHDOWN,
    TOPN_PUSHDOWN,
    LIMIT_PUSHDOWN,
    DYNAMIC_FILTER,
    STATISTICS,
    FALLBACK,
    DATA_SKIPPING
}
