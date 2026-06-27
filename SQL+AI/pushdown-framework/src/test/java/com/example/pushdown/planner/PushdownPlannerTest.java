package com.example.pushdown.planner;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PushdownPlannerTest {
    @Test
    void plannerSelectsPushFilterOverLocalOnly() {
        MockConnector connector = new MockConnector();
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, null);

        assertThat(result).isPresent();
        FilterResult fr = result.get();
        assertThat(fr.conjunctResults()).hasSize(1);
        assertThat(fr.conjunctResults().get(0).mode()).isEqualTo(PushdownMode.IN_MEMORY);
    }

    @Test
    void plannerReturnsEmptyWhenNoPushdown() {
        NoFilterConnector connector = new NoFilterConnector();
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("none").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, null);
        assertThat(result).isEmpty();
    }

    static class NoFilterConnector extends MockConnector {
        @Override
        public boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                         ConnectorExpression predicate) {
            return false;
        }
    }
}
