package com.example.pushdown.dynamicfilter;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.type.Type;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DynamicFilterLifecycleTest {
    @Test
    void pendingStateHasEmptyPredicate() {
        DynamicFilter df = DynamicFilter.pending(Set.of(new MockColumnHandle("id")));
        assertThat(df.isComplete()).isFalse();
        assertThat(df.getCurrentPredicate()).isEmpty();
        assertThat(df.getError()).isEmpty();
    }

    @Test
    void finalStateIsComplete() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("id"), Type.INTEGER), new Constant(1, Type.INTEGER));
        DynamicFilter df = DynamicFilter.finalState(Set.of(new MockColumnHandle("id")), pred);
        assertThat(df.isComplete()).isTrue();
        assertThat(df.getCurrentPredicate()).contains(pred);
    }

    @Test
    void errorStateHasError() {
        RuntimeException err = new RuntimeException("build failed");
        DynamicFilter df = DynamicFilter.error(Set.of(new MockColumnHandle("id")), err);
        assertThat(df.getError()).contains(err);
    }

    @Test
    void fullLifecyclePendingToPartialToFinal() {
        MockColumnHandle col = new MockColumnHandle("id");
        SimpleDynamicFilterSource source = new SimpleDynamicFilterSource(Set.of(col), 10000);
        
        DynamicFilterSnapshot pending = source.snapshot();
        assertThat(pending.predicate()).isEmpty();
        
        source.addValues(Set.of(1, 2, 3));
        DynamicFilterSnapshot partial = source.snapshot();
        assertThat(partial.predicate()).isPresent();
        assertThat(partial.complete()).isFalse();
        
        source.markFinal();
        DynamicFilterSnapshot finalSnap = source.snapshot();
        assertThat(finalSnap.complete()).isTrue();
    }

    @Test
    void errorStatePropagatesToSnapshot() {
        MockColumnHandle col = new MockColumnHandle("id");
        SimpleDynamicFilterSource source = new SimpleDynamicFilterSource(Set.of(col), 10000);
        source.addValues(Set.of(1));
        source.markError(new RuntimeException("crashed"));
        DynamicFilterSnapshot snap = source.snapshot();
        assertThat(snap.error()).isTrue();
    }

    @Test
    void overflowDegradesToBloomFilter() {
        MockColumnHandle col = new MockColumnHandle("id");
        SimpleDynamicFilterSource source = new SimpleDynamicFilterSource(Set.of(col), 3);
        source.addValues(Set.of(1, 2, 3, 4, 5));
        DynamicFilterSnapshot snap = source.snapshot();
        assertThat(snap.degraded()).isTrue();
    }

    @Test
    void scanContextCombinesStaticAndDynamicResiduals() {
        MockColumnHandle staticCol = new MockColumnHandle("city");
        MockColumnHandle dynCol = new MockColumnHandle("id");
        Comparison staticPred = new Comparison(Operator.EQ,
            new Variable(staticCol, Type.VARCHAR), new Constant("NYC", Type.VARCHAR));
        Comparison dynPred = new Comparison(Operator.EQ,
            new Variable(dynCol, Type.INTEGER), new Constant(42, Type.INTEGER));
        
        ConjunctPushdown staticCp = FilterResults.conjunct(
            staticPred, Optional.empty(), staticPred, PushdownMode.IN_MEMORY);
        
        ScanContext ctx = ScanContext.of(new MockTableHandle("users"),
            List.of(staticCp), DynamicFilter.pending(Set.of(dynCol)), Set.of(dynCol));
        
        ConnectorExpression residualBefore = ctx.computeEngineResidual(null);
        assertThat(residualBefore).isEqualTo(staticPred);
        
        ConnectorExpression residualAfter = ctx.computeEngineResidual(dynPred);
        assertThat(residualAfter).isInstanceOf(Logical.class);
    }

    @Test
    void scanContextExactStaticHasEmptyResidual() {
        MockColumnHandle exactCol = new MockColumnHandle("age");
        Comparison exactPred = new Comparison(Operator.EQ,
            new Variable(exactCol, Type.INTEGER), new Constant(18, Type.INTEGER));
        ConjunctPushdown exactCp = FilterResults.conjunct(
            exactPred, Optional.of(exactPred), Expressions.TRUE(), PushdownMode.EXACT);
        
        ScanContext ctx = ScanContext.of(new MockTableHandle("users"),
            List.of(exactCp), DynamicFilter.pending(Set.of()), Set.of());
        
        assertThat(ctx.computeEngineResidual(null)).isEqualTo(Expressions.TRUE());
    }

    @Test
    void scanContextReSkipOnDynamicWhenConservative() {
        MockColumnHandle consCol = new MockColumnHandle("ts");
        Comparison consPred = new Comparison(Operator.GT,
            new Variable(consCol, Type.TIMESTAMP), new Constant(0, Type.INTEGER));
        ConjunctPushdown consCp = FilterResults.conjunct(
            consPred, Optional.of(consPred), consPred, PushdownMode.CONSERVATIVE);
        
        ScanContext ctx = ScanContext.of(new MockTableHandle("events"),
            List.of(consCp), DynamicFilter.pending(Set.of()), Set.of());
        
        assertThat(ctx.shouldReSkipOnDynamic()).isTrue();
    }

    @Test
    void scanContextNoReSkipWhenOnlyExact() {
        MockColumnHandle exactCol = new MockColumnHandle("age");
        Comparison exactPred = new Comparison(Operator.EQ,
            new Variable(exactCol, Type.INTEGER), new Constant(18, Type.INTEGER));
        ConjunctPushdown exactCp = FilterResults.conjunct(
            exactPred, Optional.of(exactPred), Expressions.TRUE(), PushdownMode.EXACT);
        
        ScanContext ctx = ScanContext.of(new MockTableHandle("users"),
            List.of(exactCp), DynamicFilter.pending(Set.of()), Set.of());
        
        assertThat(ctx.shouldReSkipOnDynamic()).isFalse();
    }

    @Test
    void overflowStrategyEnumHasAllValues() {
        assertThat(OverflowStrategy.values())
            .contains(OverflowStrategy.DOWNGRADE_TO_BLOOM_FILTER,
                       OverflowStrategy.DROP_FILTER, OverflowStrategy.KEEP_PARTIAL);
    }
}
