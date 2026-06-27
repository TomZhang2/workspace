package com.example.pushdown.aggregate;

/**
 * How aggressively an aggregate was pushed down to the source.
 *
 * <ul>
 *   <li>{@link #NONE} — nothing pushed; the engine aggregates in memory.</li>
 *   <li>{@link #PARTIAL} — the source computed partial / intermediate states
 *       (e.g. {@code (sum, count)} for AVG); the engine merges them via the
 *       {@link MergeFunction} and may still apply a residual HAVING.</li>
 *   <li>{@link #COMPLETE} — the source computed the final aggregate values;
 *       the engine just relays them.</li>
 * </ul>
 */
public enum AggregateMode {
    NONE,
    PARTIAL,
    COMPLETE
}
