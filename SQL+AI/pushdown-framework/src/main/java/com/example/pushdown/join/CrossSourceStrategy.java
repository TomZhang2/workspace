package com.example.pushdown.join;

public enum CrossSourceStrategy {
    FULL_PUSH,
    FILTER_EACH_SIDE,
    BROADCAST_IN_LIST,
    SEMI_JOIN
}
