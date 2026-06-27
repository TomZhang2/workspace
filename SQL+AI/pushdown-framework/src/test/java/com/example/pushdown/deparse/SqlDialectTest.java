package com.example.pushdown.deparse;

import com.example.pushdown.type.Type;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqlDialectTest {
    @Test
    void ansiDialectQuotesWithDoubleQuotes() {
        SqlDialect ansi = SqlDialect.ANSI;
        assertThat(ansi.quoteIdentifier("users")).isEqualTo("\"users\"");
        assertThat(ansi.quoteIdentifier("name")).isEqualTo("\"name\"");
    }

    @Test
    void mysqlDialectQuotesWithBackticks() {
        SqlDialect mysql = SqlDialect.MYSQL;
        assertThat(mysql.quoteIdentifier("users")).isEqualTo("`users`");
    }

    @Test
    void ansiFormatsStringLiteral() {
        SqlDialect ansi = SqlDialect.ANSI;
        assertThat(ansi.formatLiteral("hello", Type.VARCHAR)).isEqualTo("'hello'");
    }

    @Test
    void ansiFormatsIntegerLiteral() {
        SqlDialect ansi = SqlDialect.ANSI;
        assertThat(ansi.formatLiteral(42, Type.INTEGER)).isEqualTo("42");
    }

    @Test
    void ansiFormatsBooleanLiteral() {
        SqlDialect ansi = SqlDialect.ANSI;
        assertThat(ansi.formatLiteral(true, Type.BOOLEAN)).isEqualTo("TRUE");
        assertThat(ansi.formatLiteral(false, Type.BOOLEAN)).isEqualTo("FALSE");
    }

    @Test
    void mysqlTypeMapping() {
        SqlDialect mysql = SqlDialect.MYSQL;
        assertThat(mysql.typeName(Type.INTEGER)).isEqualTo("INT");
        assertThat(mysql.typeName(Type.VARCHAR)).isEqualTo("VARCHAR");
        assertThat(mysql.typeName(Type.BOOLEAN)).isEqualTo("BOOLEAN");
    }
}
