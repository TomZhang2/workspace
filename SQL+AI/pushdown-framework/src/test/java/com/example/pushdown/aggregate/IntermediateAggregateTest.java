package com.example.pushdown.aggregate;

import com.example.pushdown.expression.FunctionSignature;
import com.example.pushdown.expression.FunctionVolatility;
import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IntermediateAggregateTest {
    @Test
    void intermediateAggregateHoldsAllFields() {
        FunctionSignature avg = new FunctionSignature(
            "AVG", List.of(Type.INTEGER), Type.DOUBLE, FunctionVolatility.IMMUTABLE);
        IntermediateAggregate ia = IntermediateAggregate.of(
            avg, Type.UNKNOWN, MergeFunctions.avg(), "avg_partial");

        assertThat(ia.originalAggregate().name()).isEqualTo("AVG");
        assertThat(ia.intermediateColumnAlias()).isEqualTo("avg_partial");
        assertThat(ia.mergeFunction()).isNotNull();
    }
}
