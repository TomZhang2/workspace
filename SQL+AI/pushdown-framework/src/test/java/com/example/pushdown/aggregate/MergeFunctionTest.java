package com.example.pushdown.aggregate;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MergeFunctionTest {
    @Test
    void sumMergeAddsValues() {
        MergeFunction sum = MergeFunctions.sum();
        Object result = sum.merge(10, 20);
        assertThat(result).isEqualTo(30);
    }

    @Test
    void sumMergeAll() {
        MergeFunction sum = MergeFunctions.sum();
        Object result = sum.mergeAll(List.of(10, 20, 30));
        assertThat(result).isEqualTo(60);
    }

    @Test
    void countMergeAddsCounts() {
        MergeFunction count = MergeFunctions.count();
        Object result = count.merge(5L, 3L);
        assertThat(result).isEqualTo(8L);
    }

    @Test
    void avgMergeCombinesSumAndCount() {
        MergeFunction avg = MergeFunctions.avg();
        // partial1: (sum=100, count=5) -> avg=20
        // partial2: (sum=50, count=5) -> avg=10
        // merged: (sum=150, count=10) -> avg=15
        Object partial1 = new MergeFunctions.AvgState(BigDecimal.valueOf(100), 5L);
        Object partial2 = new MergeFunctions.AvgState(BigDecimal.valueOf(50), 5L);
        Object merged = avg.merge(partial1, partial2);
        Object finalized = avg.finalize(merged);
        assertThat(finalized).isEqualTo(15.0);
    }

    @Test
    void minMergeReturnsSmaller() {
        MergeFunction min = MergeFunctions.min();
        assertThat(min.merge(5, 3)).isEqualTo(3);
        assertThat(min.merge(1, 10)).isEqualTo(1);
    }

    @Test
    void maxMergeReturnsLarger() {
        MergeFunction max = MergeFunctions.max();
        assertThat(max.merge(5, 3)).isEqualTo(5);
        assertThat(max.merge(1, 10)).isEqualTo(10);
    }

    @Test
    void mergeAllWithEmptyListThrows() {
        MergeFunction sum = MergeFunctions.sum();
        assertThatThrownBy(() -> sum.mergeAll(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergeAllWithSingleElementReturnsItMerged() {
        MergeFunction sum = MergeFunctions.sum();
        Object result = sum.mergeAll(List.of(42));
        assertThat(result).isEqualTo(42);
    }

    @Test
    void serializeAndDeserializeRoundTrip() {
        MergeFunction sum = MergeFunctions.sum();
        byte[] data = sum.serialize(42);
        Object result = sum.deserialize(data);
        assertThat(result).isEqualTo(42);
    }
}
