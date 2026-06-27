package com.example.pushdown.dynamicfilter;

/**
 * Delivers dynamic filter snapshots to split sources for partition/file pruning.
 */
public interface DynamicSplitSource {
    Object getNextBatch(int maxSize, DynamicFilterSnapshot snapshot);
}
