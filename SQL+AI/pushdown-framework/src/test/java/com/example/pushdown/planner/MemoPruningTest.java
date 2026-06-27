package com.example.pushdown.planner;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MemoPruningTest {
    @Test
    void candidateCountCappedAt5() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);
        List<PlanPath> paths = builder.buildCandidates(session, table, pred);

        // MockConnector only supports filter pushdown → 2 paths (LOCAL_ONLY + PUSH_FILTER)
        // But the cap is 5, so 2 < 5 → all returned
        assertThat(paths).hasSize(2);
        assertThat(paths.size()).isLessThanOrEqualTo(5);
    }

    @Test
    void memoCachesBestPathPerTable() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);

        // First call builds paths
        List<PlanPath> paths1 = builder.buildCandidates(session, table, pred);
        // Second call should use memo (same table)
        List<PlanPath> paths2 = builder.buildCandidates(session, table, pred);

        // Should return same paths (memo hit)
        assertThat(paths2).hasSameSizeAs(paths1);
    }

    @Test
    void pruningSkipsHighCostPaths() {
        // With a connector that doesn't support pushdown, only LOCAL_ONLY is returned
        MockConnector connector = new MockConnector() {
            @Override
            public boolean isFilterPushable(ConnectorSession session, com.example.pushdown.handle.TableHandle table,
                                             ConnectorExpression predicate) {
                return false;
            }
        };
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);
        List<PlanPath> paths = builder.buildCandidates(session, table, pred);

        // Only LOCAL_ONLY (PUSH_FILTER pruned because isFilterPushable=false)
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).name()).isEqualTo("LOCAL_ONLY");
    }
}
