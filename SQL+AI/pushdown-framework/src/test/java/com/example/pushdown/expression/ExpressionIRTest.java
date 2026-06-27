package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExpressionIRTest {
    record TestColumn(String name) implements ColumnHandle {}

    @Test
    void variableHoldsColumnAndType() {
        ColumnHandle col = new TestColumn("age");
        Variable var = new Variable(col, Type.INTEGER);
        assertThat(var.column().name()).isEqualTo("age");
        assertThat(var.type()).isEqualTo(Type.INTEGER);
    }

    @Test
    void constantHoldsValueAndType() {
        Constant c = new Constant(42, Type.INTEGER);
        assertThat(c.value()).isEqualTo(42);
        assertThat(c.type()).isEqualTo(Type.INTEGER);
    }

    @Test
    void callWithFunctionAndArgs() {
        FunctionSignature upper = new FunctionSignature(
            "upper", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        Variable nameCol = new Variable(new TestColumn("name"), Type.VARCHAR);
        Call call = new Call(upper, List.of(nameCol), Type.VARCHAR);
        assertThat(call.function().name()).isEqualTo("upper");
        assertThat(call.args()).hasSize(1);
        assertThat(call.type()).isEqualTo(Type.VARCHAR);
    }

    @Test
    void callVolatilityAccessedViaFunction() {
        FunctionSignature now = new FunctionSignature(
            "now", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        Call call = new Call(now, List.of(), Type.TIMESTAMP);
        assertThat(call.function().volatility()).isEqualTo(FunctionVolatility.STABLE);
    }

    @Test
    void comparisonWithOperator() {
        Variable age = new Variable(new TestColumn("age"), Type.INTEGER);
        Constant five = new Constant(5, Type.INTEGER);
        Comparison cmp = new Comparison(Operator.GT, age, five);
        assertThat(cmp.op()).isEqualTo(Operator.GT);
        assertThat(cmp.left()).isInstanceOf(Variable.class);
        assertThat(cmp.right()).isInstanceOf(Constant.class);
    }

    @Test
    void logicalAndWithTwoTerms() {
        Comparison c1 = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        Comparison c2 = new Comparison(Operator.EQ,
            new Variable(new TestColumn("b"), Type.INTEGER),
            new Constant(2, Type.INTEGER));
        Logical and = new Logical(LogicalOperator.AND, List.of(c1, c2));
        assertThat(and.op()).isEqualTo(LogicalOperator.AND);
        assertThat(and.terms()).hasSize(2);
    }

    @Test
    void castExpression() {
        Variable x = new Variable(new TestColumn("x"), Type.VARCHAR);
        Cast cast = new Cast(x, Type.INTEGER);
        assertThat(cast.targetType()).isEqualTo(Type.INTEGER);
        assertThat(cast.expr()).isEqualTo(x);
    }

    @Test
    void specialIsNull() {
        Variable name = new Variable(new TestColumn("name"), Type.VARCHAR);
        Special isNull = new Special(SpecialKind.IS_NULL, name, List.of());
        assertThat(isNull.kind()).isEqualTo(SpecialKind.IS_NULL);
        assertThat(isNull.expr()).isEqualTo(name);
        assertThat(isNull.args()).isEmpty();
    }

    @Test
    void domainHoldsMinAndMax() {
        Domain<Integer> domain = Domain.of(0, 100, Type.INTEGER);
        assertThat(domain.min()).isEqualTo(0);
        assertThat(domain.max()).isEqualTo(100);
        assertThat(domain.type()).isEqualTo(Type.INTEGER);
    }

    @Test
    void tupleDomainHoldsColumnDomains() {
        ColumnHandle age = new TestColumn("age");
        Domain<Integer> ageRange = Domain.of(18, 65, Type.INTEGER);
        TupleDomain td = new TupleDomain(Map.of(age, ageRange));
        assertThat(td.domains()).containsKey(age);
    }

    @Test
    void allTypesAreConnectorExpressions() {
        ConnectorExpression var = new Variable(new TestColumn("x"), Type.VARCHAR);
        ConnectorExpression con = new Constant("hello", Type.VARCHAR);
        ConnectorExpression call = new Call(
            new FunctionSignature("upper", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE),
            List.of(var), Type.VARCHAR);
        ConnectorExpression cmp = new Comparison(Operator.EQ, var, con);
        ConnectorExpression log = new Logical(LogicalOperator.AND, List.of(cmp));
        ConnectorExpression cast = new Cast(var, Type.INTEGER);
        ConnectorExpression spec = new Special(SpecialKind.IS_NULL, var, List.of());
        ConnectorExpression td = new TupleDomain(Map.of());

        assertThat(var).isInstanceOf(ConnectorExpression.class);
        assertThat(con).isInstanceOf(ConnectorExpression.class);
        assertThat(call).isInstanceOf(ConnectorExpression.class);
        assertThat(cmp).isInstanceOf(ConnectorExpression.class);
        assertThat(log).isInstanceOf(ConnectorExpression.class);
        assertThat(cast).isInstanceOf(ConnectorExpression.class);
        assertThat(spec).isInstanceOf(ConnectorExpression.class);
        assertThat(td).isInstanceOf(ConnectorExpression.class);
    }
}
