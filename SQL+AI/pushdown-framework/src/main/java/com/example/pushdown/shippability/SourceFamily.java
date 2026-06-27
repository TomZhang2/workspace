package com.example.pushdown.shippability;

/**
 * Identifies a family of compatible sources. Each family exposes a
 * {@link BuiltinCatalog} describing the built-in functions that the source
 * natively supports.
 */
public enum SourceFamily {
    MYSQL,
    POSTGRESQL,
    CLICKHOUSE,
    GENERIC;

    public BuiltinCatalog builtinCatalog() {
        return switch (this) {
            case MYSQL -> MysqlBuiltinCatalog.INSTANCE;
            case POSTGRESQL -> PostgresBuiltinCatalog.INSTANCE;
            case CLICKHOUSE -> ClickHouseBuiltinCatalog.INSTANCE;
            case GENERIC -> BuiltinCatalog.EMPTY;
        };
    }

    /**
     * Infer a {@link SourceFamily} from a connector server id (case-insensitive).
     * Returns {@link #GENERIC} when the server id is null or unrecognized.
     */
    public static SourceFamily fromServerId(String serverId) {
        if (serverId == null) return GENERIC;
        String lower = serverId.toLowerCase();
        if (lower.contains("mysql") || lower.contains("maria")) return MYSQL;
        if (lower.contains("postgres") || lower.contains("pg")) return POSTGRESQL;
        if (lower.contains("clickhouse") || lower.contains("ch")) return CLICKHOUSE;
        return GENERIC;
    }
}
