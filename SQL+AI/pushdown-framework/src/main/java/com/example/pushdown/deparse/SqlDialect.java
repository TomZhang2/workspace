package com.example.pushdown.deparse;

import com.example.pushdown.type.Type;

public interface SqlDialect {
    String quoteIdentifier(String identifier);
    String formatLiteral(Object value, Type type);
    String typeName(Type type);

    // Standard dialects
    SqlDialect ANSI = new AnsiDialect();
    SqlDialect MYSQL = new MySqlDialect();

    record AnsiDialect() implements SqlDialect {
        @Override
        public String quoteIdentifier(String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        public String formatLiteral(Object value, Type type) {
            return switch (type) {
                case VARCHAR -> "'" + value.toString().replace("'", "''") + "'";
                case INTEGER, BIGINT -> value.toString();
                case DOUBLE -> value.toString();
                case BOOLEAN -> Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
                default -> "'" + value.toString() + "'";
            };
        }

        @Override
        public String typeName(Type type) {
            return switch (type) {
                case INTEGER -> "INTEGER";
                case BIGINT -> "BIGINT";
                case DOUBLE -> "DOUBLE";
                case VARCHAR -> "VARCHAR";
                case BOOLEAN -> "BOOLEAN";
                case DATE -> "DATE";
                case TIMESTAMP -> "TIMESTAMP";
                case BINARY -> "BINARY";
                default -> "UNKNOWN";
            };
        }
    }

    record MySqlDialect() implements SqlDialect {
        @Override
        public String quoteIdentifier(String identifier) {
            return "`" + identifier + "`";
        }

        @Override
        public String formatLiteral(Object value, Type type) {
            return switch (type) {
                case VARCHAR -> "'" + value.toString().replace("'", "''") + "'";
                case INTEGER, BIGINT -> value.toString();
                case DOUBLE -> value.toString();
                case BOOLEAN -> Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
                default -> "'" + value.toString() + "'";
            };
        }

        @Override
        public String typeName(Type type) {
            return switch (type) {
                case INTEGER -> "INT";
                case BIGINT -> "BIGINT";
                case DOUBLE -> "DOUBLE";
                case VARCHAR -> "VARCHAR";
                case BOOLEAN -> "BOOLEAN";
                case DATE -> "DATE";
                case TIMESTAMP -> "TIMESTAMP";
                case BINARY -> "BINARY";
                default -> "UNKNOWN";
            };
        }
    }
}
