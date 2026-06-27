package com.example.pushdown.shippability;

import com.example.pushdown.expression.FunctionSignature;
import java.util.Set;

/**
 * MySQL built-in function whitelist. Names are matched case-insensitively
 * against the upper-cased set.
 *
 * <p>Phase 2: all builtins are treated as semantically compatible.
 * Phase 5 will introduce division/varchar/decimal semantic checks.
 */
class MysqlBuiltinCatalog implements BuiltinCatalog {
    static final MysqlBuiltinCatalog INSTANCE = new MysqlBuiltinCatalog();

    private static final Set<String> BUILTINS = Set.of(
        "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "MOD",
        "CONCAT", "SUBSTRING", "SUBSTR", "LENGTH", "CHAR_LENGTH",
        "TRIM", "LTRIM", "RTRIM", "LOWER", "LCASE", "UPPER", "UCASE",
        "REPLACE", "LEFT", "RIGHT",
        "COUNT", "SUM", "MIN", "MAX", "AVG",
        "NOW", "CURRENT_TIMESTAMP", "CURRENT_DATE",
        "DATE_FORMAT", "DATE_ADD", "DATE_SUB", "DATEDIFF",
        "COALESCE", "IFNULL", "NULLIF",
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
