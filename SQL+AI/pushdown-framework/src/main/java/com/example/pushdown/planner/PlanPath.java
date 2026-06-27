package com.example.pushdown.planner;

import com.example.pushdown.handle.TableHandle;

public record PlanPath(
    String name,
    boolean pushed,
    TableHandle table,
    double estimatedCost
) {
    public static PlanPath localOnly(TableHandle table) {
        return new PlanPath("LOCAL_ONLY", false, table, 1.0);
    }

    public static PlanPath pushFilter(TableHandle table) {
        return new PlanPath("PUSH_FILTER", true, table, 0.0);
    }
}
