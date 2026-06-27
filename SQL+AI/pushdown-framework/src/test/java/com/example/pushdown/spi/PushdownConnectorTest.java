package com.example.pushdown.spi;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.*;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PushdownConnectorTest {
    @Test
    void defaultIsFilterPushableReturnsFalse() {
        PushdownConnector connector = new NoOpConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();
        assertThat(connector.isFilterPushable(
            session, new TestTable("t"), Expressions.TRUE())).isFalse();
    }

    @Test
    void defaultApplyFilterReturnsEmpty() {
        PushdownConnector connector = new NoOpConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();
        assertThat(connector.applyFilter(
            session, new TestTable("t"), Expressions.TRUE(), null)).isEmpty();
    }

    @Test
    void defaultSupportsFallbackIsTrue() {
        PushdownConnector connector = new NoOpConnector();
        assertThat(connector.supportsFallback()).isTrue();
    }

    static class NoOpConnector implements PushdownConnector {
        @Override public ConnectorVersion getVersion() { return ConnectorVersion.V2; }
        @Override public Set<ConnectorCapability> capabilities(TableHandle table) { return Set.of(); }
    }

    record TestTable(String name) implements TableHandle {}
}
