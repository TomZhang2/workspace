package com.example.pushdown.invariant;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResidualInvariantValidatorTest {
    record TestColumn(String name) implements ColumnHandle {}

    private final ResidualInvariantValidator validator = new ResidualInvariantValidator();

    @Test
    void exactConjunctWithEmptyResidualIsValid() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.of(pred), Expressions.TRUE(), PushdownMode.EXACT);
        assertThatCode(() -> validator.validateFilter(pred, List.of(cp)))
            .doesNotThrowAnyException();
    }

    @Test
    void conservativeConjunctWithFullResidualIsValid() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.of(pred), pred, PushdownMode.CONSERVATIVE);
        assertThatCode(() -> validator.validateFilter(pred, List.of(cp)))
            .doesNotThrowAnyException();
    }

    @Test
    void conservativeConjunctWithWrongResidualThrows() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        Comparison other = new Comparison(Operator.GT,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(0, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.of(pred), other, PushdownMode.CONSERVATIVE);
        assertThatThrownBy(() -> validator.validateFilter(pred, List.of(cp)))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void inMemoryConjunctWithNoPushedAndFullResidualIsValid() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.empty(), pred, PushdownMode.IN_MEMORY);
        assertThatCode(() -> validator.validateFilter(pred, List.of(cp)))
            .doesNotThrowAnyException();
    }

    @Test
    void inMemoryConjunctWithPushedThrows() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.of(pred), pred, PushdownMode.IN_MEMORY);
        assertThatThrownBy(() -> validator.validateFilter(pred, List.of(cp)))
            .isInstanceOf(AssertionError.class);
    }
}
