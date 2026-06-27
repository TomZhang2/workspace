package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExpressionsTest {
    record TestColumn(String name) implements ColumnHandle {}

    @Test
    void logicalAndCombinesTwoExpressions() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        Comparison b = new Comparison(Operator.EQ,
            new Variable(new TestColumn("b"), Type.INTEGER),
            new Constant(2, Type.INTEGER));
        ConnectorExpression result = Expressions.logicalAnd(a, b);
        assertThat(result).isInstanceOf(Logical.class);
        assertThat(((Logical) result).op()).isEqualTo(LogicalOperator.AND);
        assertThat(((Logical) result).terms()).hasSize(2);
    }

    @Test
    void logicalAndWithSingleExpressionReturnsItself() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        ConnectorExpression result = Expressions.logicalAnd(a);
        assertThat(result).isEqualTo(a);
    }

    @Test
    void trueConstantExists() {
        assertThat(Expressions.TRUE()).isInstanceOf(Constant.class);
        assertThat(((Constant) Expressions.TRUE()).value()).isEqualTo(true);
    }

    @Test
    void splitConjunctsBreaksTopLevelAnd() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER), new Constant(1, Type.INTEGER));
        Comparison b = new Comparison(Operator.EQ,
            new Variable(new TestColumn("b"), Type.INTEGER), new Constant(2, Type.INTEGER));
        Comparison c = new Comparison(Operator.EQ,
            new Variable(new TestColumn("c"), Type.INTEGER), new Constant(3, Type.INTEGER));
        Logical and = new Logical(LogicalOperator.AND, List.of(a, b, c));
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(and);
        assertThat(conjuncts).hasSize(3);
    }

    @Test
    void splitConjunctsOfSingleComparisonReturnsOne() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER), new Constant(1, Type.INTEGER));
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(a);
        assertThat(conjuncts).hasSize(1);
        assertThat(conjuncts.get(0)).isEqualTo(a);
    }
}
