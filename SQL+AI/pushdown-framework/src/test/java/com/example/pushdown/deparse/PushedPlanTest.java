package com.example.pushdown.deparse;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PushedPlanTest {
    @Test
    void pushedPlanHoldsAllFields() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(pred, Optional.of(pred), Expressions.TRUE(), PushdownMode.EXACT);

        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("users"))
            .projections(List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(List.of(cp))
            .build();

        assertThat(plan.tableHandle().name()).isEqualTo("users");
        assertThat(plan.projections()).hasSize(1);
        assertThat(plan.conjunctResults()).hasSize(1);
        assertThat(plan.groupingKeys()).isEmpty();
        assertThat(plan.pushedHaving()).isEmpty();
        assertThat(plan.sortItems()).isEmpty();
        assertThat(plan.limit()).isEmpty();
        assertThat(plan.fetchSize()).isEqualTo(100);
    }

    @Test
    void pushedPlanResidualCombinesConjunctResiduals() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("a"), Type.INTEGER), new Constant(1, Type.INTEGER));
        Comparison b = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("b"), Type.INTEGER), new Constant(2, Type.INTEGER));
        ConjunctPushdown cpA = FilterResults.conjunct(a, Optional.of(a), Expressions.TRUE(), PushdownMode.EXACT);
        ConjunctPushdown cpB = FilterResults.conjunct(b, Optional.empty(), b, PushdownMode.IN_MEMORY);

        PushedPlan plan = PushedPlan.builder()
            .tableHandle(new MockTableHandle("t"))
            .conjunctResults(List.of(cpA, cpB))
            .build();

        // residual = TRUE ∧ b = b
        assertThat(plan.residual()).isEqualTo(b);
    }
}
