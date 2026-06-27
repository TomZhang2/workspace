package com.example.pushdown.planner;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PushdownPathBuilderTest {
    @Test
    void buildsLocalOnlyAndPushFilterCandidates() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);
        List<PlanPath> paths = builder.buildCandidates(session, table, pred);

        assertThat(paths).hasSizeGreaterThanOrEqualTo(2);
        assertThat(paths.stream().map(PlanPath::name))
            .contains("LOCAL_ONLY", "PUSH_FILTER");
    }

    @Test
    void localOnlyPathHasNoPushdown() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);
        List<PlanPath> paths = builder.buildCandidates(session, table, pred);

        PlanPath localOnly = paths.stream()
            .filter(p -> p.name().equals("LOCAL_ONLY")).findFirst().orElseThrow();
        assertThat(localOnly.pushed()).isFalse();
    }

    @Test
    void pushFilterPathIsPushed() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);
        List<PlanPath> paths = builder.buildCandidates(session, table, pred);

        PlanPath pushFilter = paths.stream()
            .filter(p -> p.name().equals("PUSH_FILTER")).findFirst().orElseThrow();
        assertThat(pushFilter.pushed()).isTrue();
    }
}
