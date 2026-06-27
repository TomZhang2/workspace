package com.example.pushdown.shippability;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ShippabilityCheckerTest {
    private final ShippabilityChecker checker = new ShippabilityChecker();
    private final ConnectorSession session = ConnectorSession.builder()
        .user("test").queryId("q1").serverId("mysql-prod").build();

    @Test
    void variableIsShippable() {
        Variable var = new Variable(new MockColumnHandle("age"), Type.INTEGER);
        assertThat(checker.isShippable(var, session, null)).isTrue();
    }

    @Test
    void constantIsShippable() {
        Constant c = new Constant(42, Type.INTEGER);
        assertThat(checker.isShippable(c, session, null)).isTrue();
    }

    @Test
    void immutableBuiltinFunctionIsShippable() {
        FunctionSignature upper = new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        Call call = new Call(upper, List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)), Type.VARCHAR);
        assertThat(checker.isShippable(call, session, null)).isTrue();
    }

    @Test
    void volatileFunctionIsNotShippable() {
        FunctionSignature random = new FunctionSignature("RANDOM", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        Call call = new Call(random, List.of(), Type.DOUBLE);
        assertThat(checker.isShippable(call, session, null)).isFalse();
    }

    @Test
    void comparisonIsShippableWhenArgsAreShippable() {
        Comparison cmp = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        assertThat(checker.isShippable(cmp, session, null)).isTrue();
    }

    @Test
    void logicalAndIsShippableWhenAllTermsShippable() {
        Comparison c1 = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("a"), Type.INTEGER), new Constant(1, Type.INTEGER));
        Comparison c2 = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("b"), Type.INTEGER), new Constant(2, Type.INTEGER));
        Logical and = new Logical(LogicalOperator.AND, List.of(c1, c2));
        assertThat(checker.isShippable(and, session, null)).isTrue();
    }

    @Test
    void logicalAndNotShippableWhenAnyTermNotShippable() {
        FunctionSignature random = new FunctionSignature("RANDOM", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        Comparison c1 = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("a"), Type.INTEGER), new Constant(1, Type.INTEGER));
        Comparison c2 = new Comparison(Operator.GT,
            new Call(random, List.of(), Type.DOUBLE), new Constant(0.5, Type.DOUBLE));
        Logical and = new Logical(LogicalOperator.AND, List.of(c1, c2));
        assertThat(checker.isShippable(and, session, null)).isFalse();
    }

    @Test
    void unknownFunctionNotShippable() {
        FunctionSignature custom = new FunctionSignature("my_custom", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        Call call = new Call(custom, List.of(new Variable(new MockColumnHandle("name"), Type.VARCHAR)), Type.VARCHAR);
        assertThat(checker.isShippable(call, session, null)).isFalse();
    }

    @Test
    void castIsShippableWhenExprShippable() {
        Cast cast = new Cast(new Variable(new MockColumnHandle("x"), Type.VARCHAR), Type.INTEGER);
        assertThat(checker.isShippable(cast, session, null)).isTrue();
    }
}
