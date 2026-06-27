package com.example.pushdown;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.invariant.ResidualInvariantValidator;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.planner.*;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class E2EFilterPushdownTest {

    private final ResidualInvariantValidator validator = new ResidualInvariantValidator();

    @Test
    void singleEqualityFilterPushdownEndToEnd() {
        MockConnector connector = new MockConnector();
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q-001").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        ConnectorExpression predicate = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, predicate, null);

        assertThat(result).isPresent();
        FilterResult fr = result.get();

        assertThat(fr.conjunctResults()).hasSize(1);
        ConjunctPushdown cp = fr.conjunctResults().get(0);
        assertThat(cp.mode()).isEqualTo(PushdownMode.IN_MEMORY);
        assertThat(cp.pushedExpression()).isEmpty();
        assertThat(cp.residualExpression()).isEqualTo(predicate);

        assertThatCode(() -> validator.validateFilter(predicate, fr.conjunctResults()))
            .doesNotThrowAnyException();

        assertThat(fr.combinedResidual()).isEqualTo(predicate);
    }

    @Test
    void conjunctiveFilterSplitIntoMultipleConjuncts() {
        MockConnector connector = new MockConnector();
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q-002").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison agePred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        Comparison namePred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("name"), Type.VARCHAR),
            new Constant("Bob", Type.VARCHAR));
        ConnectorExpression predicate = Expressions.logicalAnd(agePred, namePred);

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, predicate, null);

        assertThat(result).isPresent();
        FilterResult fr = result.get();

        assertThat(fr.conjunctResults()).hasSize(2);
        assertThat(fr.conjunctResults()).allMatch(cp -> cp.mode() == PushdownMode.IN_MEMORY);

        assertThatCode(() -> validator.validateFilter(predicate, fr.conjunctResults()))
            .doesNotThrowAnyException();

        ConnectorExpression residual = fr.combinedResidual();
        assertThat(residual).isInstanceOf(Logical.class);
        assertThat(((Logical) residual).terms()).hasSize(2);
    }

    @Test
    void noPushdownWhenConnectorDoesNotSupport() {
        MockConnector connector = new MockConnector() {
            @Override
            public boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                             ConnectorExpression predicate) {
                return false;
            }
        };
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q-003").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, null);
        assertThat(result).isEmpty();
    }
}
