package com.example.pushdown.topn;

import com.example.pushdown.handle.TableHandle;
import java.util.Optional;

public interface TopNResult {
    TableHandle pushedTable();
    boolean orderGuaranteed();
    boolean limitGuaranteed();
    Optional<Collation> sortCollation();

    default boolean isOrderTrustworthy(Collation expectedCollation) {
        return orderGuaranteed()
            && sortCollation().isPresent()
            && sortCollation().get().equals(expectedCollation);
    }

    static TopNResult of(TableHandle table, boolean orderGuaranteed,
                          boolean limitGuaranteed, Optional<Collation> collation) {
        return new ImmutableTopNResult(table, orderGuaranteed, limitGuaranteed, collation);
    }

    record ImmutableTopNResult(
        TableHandle pushedTable,
        boolean orderGuaranteed,
        boolean limitGuaranteed,
        Optional<Collation> sortCollation
    ) implements TopNResult {}
}
