package com.example.pushdown.expression;

import com.example.pushdown.type.Type;

public record Domain<T>(T min, T max, Type type) {
    public static <T> Domain<T> of(T min, T max, Type type) {
        return new Domain<>(min, max, type);
    }
}
