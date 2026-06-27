package com.example.pushdown.spi;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.session.ConnectorSession;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AggregatePushdownSPITest {
    @Test
    void defaultIsAggregatePushableReturnsFalse() {
        PushdownConnector connector = new TestConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();
        MockTableHandle table = new MockTableHandle("users");

        assertThat(connector.isAggregatePushable(
            session, table,
            List.of(), List.of(new MockColumnHandle("age")), Expressions.TRUE()
        )).isFalse();
    }

    @Test
    void defaultApplyAggregateReturnsEmpty() {
        PushdownConnector connector = new TestConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();
        MockTableHandle table = new MockTableHandle("users");

        assertThat(connector.applyAggregate(
            session, table,
            List.of(), List.of(new MockColumnHandle("age")), Expressions.TRUE()
        )).isEmpty();
    }

    @Test
    void defaultJoinPushableReturnsFalse() {
        PushdownConnector connector = new TestConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();

        assertThat(connector.isJoinPushable(
            session, null,
            new MockTableHandle("a"), new MockTableHandle("b"),
            Expressions.TRUE()
        )).isFalse();
    }

    @Test
    void defaultTopNPushableReturnsFalse() {
        PushdownConnector connector = new TestConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();

        assertThat(connector.isTopNPushable(
            session, new MockTableHandle("users"), 10, List.of()
        )).isFalse();
    }

    @Test
    void defaultLimitPushableReturnsFalse() {
        PushdownConnector connector = new TestConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();

        assertThat(connector.isLimitPushable(
            session, new MockTableHandle("users"), 10
        )).isFalse();
    }

    static class TestConnector implements PushdownConnector {
        @Override public ConnectorVersion getVersion() { return ConnectorVersion.V2; }
        @Override public Set<ConnectorCapability> capabilities(TableHandle table) { return Set.of(); }
    }
}
