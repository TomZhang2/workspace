package com.example.pushdown.spi;

import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.join.JoinType;
import com.example.pushdown.session.ConnectorSession;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JoinPushdownSPITest {
    @Test
    void defaultIsJoinPushableReturnsFalse() {
        PushdownConnector connector = new TestConn();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();
        
        assertThat(connector.isJoinPushable(
            session, JoinType.INNER,
            new MockTableHandle("a"), new MockTableHandle("b"),
            Expressions.TRUE()
        )).isFalse();
    }

    @Test
    void defaultApplyJoinReturnsEmpty() {
        PushdownConnector connector = new TestConn();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();
        
        assertThat(connector.applyJoin(
            session, JoinType.INNER,
            new MockTableHandle("a"), new MockTableHandle("b"),
            Expressions.TRUE()
        )).isEmpty();
    }

    static class TestConn implements PushdownConnector {
        @Override public ConnectorVersion getVersion() { return ConnectorVersion.V2; }
        @Override public java.util.Set<ConnectorCapability> capabilities(com.example.pushdown.handle.TableHandle table) { return java.util.Set.of(); }
    }
}
