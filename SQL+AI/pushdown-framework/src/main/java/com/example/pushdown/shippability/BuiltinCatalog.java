package com.example.pushdown.shippability;

import com.example.pushdown.expression.FunctionSignature;

/**
 * Per-source-family function whitelist with semantic compatibility checking.
 * Phase 2: all builtins are considered semantically compatible.
 * Phase 5 will add division/varchar/decimal semantic checks.
 */
public interface BuiltinCatalog {
    boolean contains(FunctionSignature sig);
    boolean isSemanticallyCompatible(FunctionSignature sig);

    BuiltinCatalog EMPTY = new BuiltinCatalog() {
        @Override public boolean contains(FunctionSignature sig) { return false; }
        @Override public boolean isSemanticallyCompatible(FunctionSignature sig) { return false; }
    };
}
