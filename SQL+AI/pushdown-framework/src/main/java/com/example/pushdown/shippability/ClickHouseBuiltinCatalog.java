package com.example.pushdown.shippability;

import com.example.pushdown.expression.FunctionSignature;
import java.util.Set;

/**
 * ClickHouse built-in function whitelist. Names are matched case-insensitively
 * against the upper-cased set.
 *
 * <p>Phase 2: all builtins are treated as semantically compatible.
 * Phase 5 will introduce division/varchar/decimal semantic checks.
 */
class ClickHouseBuiltinCatalog implements BuiltinCatalog {
    static final ClickHouseBuiltinCatalog INSTANCE = new ClickHouseBuiltinCatalog();

    private static final Set<String> BUILTINS = Set.of(
        "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "MODULO", "MOD",
        "CONCAT", "SUBSTRING", "SUBSTR", "LENGTH", "CHAR_LENGTH",
        "TRIM", "TRIMLEFT", "TRIMRIGHT", "LOWER", "UPPER",
        "REPLACE", "LEFT", "RIGHT",
        "COUNT", "SUM", "MIN", "MAX", "AVG",
        "NOW", "CURRENT_TIMESTAMP", "CURRENT_DATE",
        "TO_CHAR", "TO_DATE", "TO_TIMESTAMP",
        "DATE_TRUNC", "DATE_DIFF", "DATEDIFF",
        "COALESCE", "NULLIF",
        "CAST"
    );

    @Override
    public boolean contains(FunctionSignature sig) {
        return BUILTINS.contains(sig.name().toUpperCase());
    }

    @Override
    public boolean isSemanticallyCompatible(FunctionSignature sig) {
        // Phase 2: all builtins are semantically compatible.
        // Phase 5 will add division/varchar/decimal semantic checks.
        return contains(sig);
    }
}
