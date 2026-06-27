package com.example.pushdown.expression;

public enum Operator {
    EQ("="),
    NEQ("<>"),
    LT("<"),
    GT(">"),
    LTE("<="),
    GTE(">=");

    private final String symbol;

    Operator(String symbol) { this.symbol = symbol; }

    public String symbol() { return symbol; }

    public boolean isRange() {
        return this == LT || this == GT || this == LTE || this == GTE;
    }
}
