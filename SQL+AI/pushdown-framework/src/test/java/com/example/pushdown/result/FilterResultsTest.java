package com.example.pushdown.result;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FilterResultsTest {
    record TestColumn(String name) implements ColumnHandle {}
    record TestTable(String name) implements TableHandle {}

    @Test
    void conjunctPushdownHoldsAllFields() {
        Comparison original = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(
            original, Optional.of(original), Expressions.TRUE(), PushdownMode.EXACT);
        assertThat(cp.originalConjunct()).isEqualTo(original);
        assertThat(cp.pushedExpression()).contains(original);
        assertThat(cp.mode()).isEqualTo(PushdownMode.EXACT);
    }

    @Test
    void filterResultCombinedResidual() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER), new Constant(1, Type.INTEGER));
        Comparison b = new Comparison(Operator.EQ,
            new Variable(new TestColumn("b"), Type.INTEGER), new Constant(2, Type.INTEGER));

        ConjunctPushdown cpA = FilterResults.conjunct(
            a, Optional.of(a), Expressions.TRUE(), PushdownMode.EXACT);
        ConjunctPushdown cpB = FilterResults.conjunct(
            b, Optional.empty(), b, PushdownMode.IN_MEMORY);

        FilterResult result = FilterResults.of(new TestTable("t"), List.of(cpA, cpB));

        // combinedResidual = TRUE ∧ b = b
        ConnectorExpression residual = result.combinedResidual();
        assertThat(residual).isEqualTo(b);
    }

    @Test
    void filterResultEmptyConjunctsResidualIsTrue() {
        FilterResult result = FilterResults.of(new TestTable("t"), List.of());
        assertThat(result.combinedResidual()).isEqualTo(Expressions.TRUE());
    }
}
