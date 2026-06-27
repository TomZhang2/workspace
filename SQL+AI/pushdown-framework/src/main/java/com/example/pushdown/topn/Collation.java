package com.example.pushdown.topn;

public record Collation(String name) {
    public static Collation of(String name) { return new Collation(name); }
}
