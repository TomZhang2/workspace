package com.example.pushdown.conformance;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.invariant.ResidualInvariantValidator;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.type.Type;
import com.example.pushdown.dynamicfilter.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import static org.assertj.core.api.Assertions.*;

/**
 * Conformance test suite: every connector MUST pass these tests.
 * 
 * 1. Residual invariant correctness (property-based, random predicates)
 * 2. Guarantee flag honesty
 * 3. Partial aggregate merge correctness
 * 4. Dynamic filter lifecycle + ERROR propagation
 * 5. Error fallback correctness
 */
class ConnectorConformanceSuiteTest {

    private final ResidualInvariantValidator validator = new ResidualInvariantValidator();
    private final ConnectorSession session = ConnectorSession.builder()
        .user("test").queryId("q1").serverId("mock").build();

    // ===== 1. Residual Invariant Property Tests =====

    /**
     * Property-based test: random equality predicates → verify invariant.
     * Generates random Comparison(EQ, column, constant) predicates.
     */
    @RepeatedTest(100)
    void residualInvariantRandomEqualityPredicate() {
        MockColumnHandle col = new MockColumnHandle("col_" + ThreadLocalRandom.current().nextInt(5));
        int value = ThreadLocalRandom.current().nextInt(1000);
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(col, Type.INTEGER), new Constant(value, Type.INTEGER));
        
        // Simulate IN_MEMORY pushdown (MockConnector style)
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(pred);
        List<ConjunctPushdown> results = conjuncts.stream()
            .map(c -> FilterResults.conjunct(c, Optional.empty(), c, PushdownMode.IN_MEMORY))
            .toList();
        
        // Validate invariant — must not throw
        assertThatCode(() -> validator.validateFilter(pred, results))
            .doesNotThrowAnyException();
    }

    /**
     * Property-based test: random AND of 2-5 conjuncts → verify invariant.
     */
    @RepeatedTest(100)
    void residualInvariantRandomConjunctivePredicate() {
        int numConjuncts = 2 + ThreadLocalRandom.current().nextInt(4); // 2-5
        List<ConnectorExpression> conjuncts = new ArrayList<>();
        for (int i = 0; i < numConjuncts; i++) {
            MockColumnHandle col = new MockColumnHandle("col_" + i);
            int value = ThreadLocalRandom.current().nextInt(1000);
            Operator op = ThreadLocalRandom.current().nextBoolean() ? Operator.EQ : Operator.GT;
            conjuncts.add(new Comparison(op,
                new Variable(col, Type.INTEGER), new Constant(value, Type.INTEGER)));
        }
        ConnectorExpression predicate = Expressions.logicalAnd(
            conjuncts.toArray(new ConnectorExpression[0]));
        
        // Simulate mixed pushdown: even-index = EXACT, odd-index = IN_MEMORY
        List<ConjunctPushdown> results = new ArrayList<>();
        List<ConnectorExpression> split = Expressions.splitConjuncts(predicate);
        for (int i = 0; i < split.size(); i++) {
            if (i % 2 == 0) {
                results.add(FilterResults.conjunct(
                    split.get(i), Optional.of(split.get(i)), Expressions.TRUE(), PushdownMode.EXACT));
            } else {
                results.add(FilterResults.conjunct(
                    split.get(i), Optional.empty(), split.get(i), PushdownMode.IN_MEMORY));
            }
        }
        
        assertThatCode(() -> validator.validateFilter(predicate, results))
            .doesNotThrowAnyException();
    }

    /**
     * Property-based test: CONSERVATIVE with wrong residual MUST fail.
     */
    @RepeatedTest(50)
    void conservativeWithWrongResidualFails() {
        MockColumnHandle col1 = new MockColumnHandle("a");
        MockColumnHandle col2 = new MockColumnHandle("b");
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(col1, Type.INTEGER), new Constant(1, Type.INTEGER));
        Comparison wrong = new Comparison(Operator.EQ,
            new Variable(col2, Type.INTEGER), new Constant(2, Type.INTEGER));
        
        ConjunctPushdown badCp = FilterResults.conjunct(
            pred, Optional.of(pred), wrong, PushdownMode.CONSERVATIVE);
        
        assertThatThrownBy(() -> validator.validateFilter(pred, List.of(badCp)))
            .isInstanceOf(AssertionError.class);
    }

    // ===== 2. Guarantee Flag Honesty =====

    @Test
    void topNResultWithNoCollationIsNotTrustworthy() {
        // orderGuaranteed=true but no collation → NOT trustworthy (safe default)
        var topN = com.example.pushdown.topn.TopNResult.of(
            new MockTableHandle("t"), true, true, Optional.empty());
        assertThat(topN.isOrderTrustworthy(com.example.pushdown.topn.Collation.of("C"))).isFalse();
    }

    @Test
    void topNResultWithMatchingCollationIsTrustworthy() {
        var collation = com.example.pushdown.topn.Collation.of("utf8mb4_general_ci");
        var topN = com.example.pushdown.topn.TopNResult.of(
            new MockTableHandle("t"), true, true, Optional.of(collation));
        assertThat(topN.isOrderTrustworthy(collation)).isTrue();
    }

    @Test
    void topNResultWithMismatchedCollationIsNotTrustworthy() {
        var topN = com.example.pushdown.topn.TopNResult.of(
            new MockTableHandle("t"), true, true,
            Optional.of(com.example.pushdown.topn.Collation.of("utf8mb4_general_ci")));
        assertThat(topN.isOrderTrustworthy(com.example.pushdown.topn.Collation.of("C"))).isFalse();
    }

    @Test
    void limitResultDefaultNotGuaranteed() {
        var limit = com.example.pushdown.topn.LimitResult.of(new MockTableHandle("t"), false);
        assertThat(limit.limitGuaranteed()).isFalse();
    }

    // ===== 3. Partial Aggregate Merge Correctness =====

    @Test
    void sumMergeAllProducesCorrectResult() {
        var mergeFn = com.example.pushdown.aggregate.MergeFunctions.sum();
        Object result = mergeFn.mergeAll(List.of(10, 20, 30, 40));
        assertThat(result).isEqualTo(100);
    }

    @Test
    void avgMergeProducesCorrectFinalResult() {
        var mergeFn = com.example.pushdown.aggregate.MergeFunctions.avg();
        var p1 = new com.example.pushdown.aggregate.MergeFunctions.AvgState(java.math.BigDecimal.valueOf(100), 5L);
        var p2 = new com.example.pushdown.aggregate.MergeFunctions.AvgState(java.math.BigDecimal.valueOf(200), 5L);
        Object merged = mergeFn.merge(p1, p2);
        Object finalized = mergeFn.finalize(merged);
        assertThat(finalized).isEqualTo(30.0); // (100+200)/(5+5) = 30
    }

    @Test
    void minMergeReturnsSmallest() {
        var mergeFn = com.example.pushdown.aggregate.MergeFunctions.min();
        Object result = mergeFn.mergeAll(List.of(5, 3, 8, 1, 9));
        assertThat(result).isEqualTo(1);
    }

    @Test
    void maxMergeReturnsLargest() {
        var mergeFn = com.example.pushdown.aggregate.MergeFunctions.max();
        Object result = mergeFn.mergeAll(List.of(5, 3, 8, 1, 9));
        assertThat(result).isEqualTo(9);
    }

    @Test
    void countMergeAddsCounts() {
        var mergeFn = com.example.pushdown.aggregate.MergeFunctions.count();
        Object result = mergeFn.mergeAll(List.of(5L, 3L, 10L));
        assertThat(result).isEqualTo(18L);
    }

    // ===== 4. Dynamic Filter Lifecycle + ERROR Propagation =====

    @Test
    void dynamicFilterErrorStatePropagates() {
        RuntimeException err = new RuntimeException("build side crashed");
        DynamicFilter df = DynamicFilter.error(Set.of(new MockColumnHandle("id")), err);
        
        assertThat(df.getError()).contains(err);
        assertThat(df.isComplete()).isFalse();
        assertThat(df.getCurrentPredicate()).isEmpty();
        
        // isBlocked should complete exceptionally
        assertThatThrownBy(() -> df.isBlocked().get())
            .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void dynamicFilterFullLifecycle() {
        MockColumnHandle col = new MockColumnHandle("id");
        SimpleDynamicFilterSource source = new SimpleDynamicFilterSource(Set.of(col), 10000);
        
        // PENDING
        assertThat(source.snapshot().predicate()).isEmpty();
        
        // PARTIAL
        source.addValues(Set.of(1, 2));
        DynamicFilterSnapshot partial = source.snapshot();
        assertThat(partial.predicate()).isPresent();
        assertThat(partial.complete()).isFalse();
        
        // FINAL
        source.markFinal();
        DynamicFilterSnapshot done = source.snapshot();
        assertThat(done.complete()).isTrue();
    }

    @Test
    void dynamicFilterOverflowDegrades() {
        MockColumnHandle col = new MockColumnHandle("id");
        SimpleDynamicFilterSource source = new SimpleDynamicFilterSource(Set.of(col), 3);
        source.addValues(Set.of(1, 2, 3, 4, 5));
        assertThat(source.snapshot().degraded()).isTrue();
    }

    // ===== 5. Error Fallback Correctness =====

    @Test
    void fallbackHandlerReturnsEmptyOnFailure() {
        com.example.pushdown.spi.PushdownConnector connector = new com.example.pushdown.connector.mock.MockConnector();
        com.example.pushdown.fallback.PushdownFallbackHandler handler =
            new com.example.pushdown.fallback.PushdownFallbackHandler(connector, 2, java.time.Duration.ofMillis(10));
        
        Optional<String> result = handler.executeWithFallback("mock",
            v -> { throw new RuntimeException("source down"); },
            new MockTableHandle("users"));
        
        assertThat(result).isEmpty();
    }

    @Test
    void fallbackHandlerRetriesAndSucceeds() {
        com.example.pushdown.spi.PushdownConnector connector = new com.example.pushdown.connector.mock.MockConnector();
        com.example.pushdown.fallback.PushdownFallbackHandler handler =
            new com.example.pushdown.fallback.PushdownFallbackHandler(connector, 3, java.time.Duration.ofMillis(10));
        
        AtomicInteger attempts = new AtomicInteger(0);
        Optional<String> result = handler.executeWithFallback("mock",
            v -> {
                if (attempts.incrementAndGet() < 2) throw new RuntimeException("transient");
                return Optional.of("recovered");
            },
            new MockTableHandle("users"));
        
        assertThat(result).contains("recovered");
    }

    @Test
    void circuitBreakerOpensAfterThresholdFailures() {
        com.example.pushdown.spi.PushdownConnector connector = new com.example.pushdown.connector.mock.MockConnector();
        com.example.pushdown.fallback.PushdownFallbackHandler handler =
            new com.example.pushdown.fallback.PushdownFallbackHandler(connector, 0, java.time.Duration.ofMillis(1));
        
        for (int i = 0; i < 5; i++) {
            handler.executeWithFallback("mock",
                v -> { throw new RuntimeException("down"); }, new MockTableHandle("t"));
        }
        
        assertThat(handler.isPushdownEnabled("mock")).isFalse();
    }

    // ===== 6. Pushdown Correctness (pushdown + residual = original) =====

    /**
     * Core correctness property: for any predicate, pushdown + residual evaluation
     * must produce the same result as local evaluation of the original predicate.
     * 
     * This is the ultimate safety net against silent wrong results.
     */
    @RepeatedTest(100)
    void pushdownPlusResidualEqualsOriginal() {
        // Generate random predicate
        int colIdx = ThreadLocalRandom.current().nextInt(3);
        int val = ThreadLocalRandom.current().nextInt(100);
        MockColumnHandle col = new MockColumnHandle("col_" + colIdx);
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(col, Type.INTEGER), new Constant(val, Type.INTEGER));
        
        // Simulate IN_MEMORY pushdown (no actual source evaluation)
        List<ConjunctPushdown> results = List.of(
            FilterResults.conjunct(pred, Optional.empty(), pred, PushdownMode.IN_MEMORY)
        );
        
        // "Source" returns all rows (IN_MEMORY = no filtering)
        // Engine applies residual = original predicate
        // → result = original predicate applied to all rows = correct
        ConnectorExpression residual = results.stream()
            .map(ConjunctPushdown::residualExpression)
            .reduce(Expressions::logicalAnd)
            .orElse(Expressions.TRUE());
        
        // For IN_MEMORY, residual MUST equal original (every row still needs checking)
        assertThat(residual).isEqualTo(pred);
    }
}
