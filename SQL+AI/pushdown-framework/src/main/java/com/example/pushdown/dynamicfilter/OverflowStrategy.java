package com.example.pushdown.dynamicfilter;

public enum OverflowStrategy {
    DOWNGRADE_TO_BLOOM_FILTER,
    DROP_FILTER,
    KEEP_PARTIAL
}
