package com.example.pushdown.deparse;

import java.util.List;

public interface DeparsedQuery {
    String sql();
    List<Integer> retrievedAttrs();
    List<Object> parameters();
    int fetchSize();

    static DeparsedQuery of(String sql, List<Integer> retrievedAttrs, List<Object> parameters, int fetchSize) {
        return new ImmutableDeparsedQuery(sql, retrievedAttrs, parameters, fetchSize);
    }

    record ImmutableDeparsedQuery(
        String sql,
        List<Integer> retrievedAttrs,
        List<Object> parameters,
        int fetchSize
    ) implements DeparsedQuery {}
}
