package com.example.pushdown.expression;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EnumTest {
    @Test
    void operatorSymbols() {
        assertThat(Operator.EQ.symbol()).isEqualTo("=");
        assertThat(Operator.NEQ.symbol()).isEqualTo("<>");
        assertThat(Operator.LT.symbol()).isEqualTo("<");
        assertThat(Operator.GT.symbol()).isEqualTo(">");
        assertThat(Operator.LTE.symbol()).isEqualTo("<=");
        assertThat(Operator.GTE.symbol()).isEqualTo(">=");
    }

    @Test
    void operatorIsRange() {
        assertThat(Operator.LT.isRange()).isTrue();
        assertThat(Operator.GT.isRange()).isTrue();
        assertThat(Operator.EQ.isRange()).isFalse();
    }

    @Test
    void logicalOperators() {
        assertThat(LogicalOperator.AND).isNotEqualTo(LogicalOperator.OR);
    }

    @Test
    void specialKinds() {
        assertThat(SpecialKind.IS_NULL).isNotEqualTo(SpecialKind.IN);
    }

    @Test
    void volatilityOrdering() {
        assertThat(FunctionVolatility.IMMUTABLE)
            .isNotEqualTo(FunctionVolatility.VOLATILE);
    }
}
