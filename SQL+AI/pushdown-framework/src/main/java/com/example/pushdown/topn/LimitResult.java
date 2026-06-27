package com.example.pushdown.topn;

import com.example.pushdown.handle.TableHandle;

public interface LimitResult {
    TableHandle pushedTable();
    boolean limitGuaranteed();

    static LimitResult of(TableHandle table, boolean limitGuaranteed) {
        return new ImmutableLimitResult(table, limitGuaranteed);
    }

    record ImmutableLimitResult(TableHandle pushedTable, boolean limitGuaranteed) implements LimitResult {}
}
