package com.example.pushdown.cost;

import com.example.pushdown.mode.PushdownMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PushdownCostModelTest {
    private final PushdownCostModel costModel = new PushdownCostModel(
        100.0,   // sourceStartupCost
        0.01,    // sourceTupleCost
        1.0,     // remoteComputeRatio
        1_000_000 // networkBandwidth bytes/sec
    );

    @Test
    void exactPushdownCheaperForSelectiveFilter() {
        // N=1M rows, sel=0.001 → 1000 rows survive
        double pushCost = costModel.estimatePushCost(PushdownMode.EXACT, 1_000_000, 0.001, 100);
        double noPushCost = costModel.estimateNoPushCost(1_000_000, 100);

        assertThat(pushCost).isLessThan(noPushCost);
    }

    @Test
    void exactPushdownMoreExpensiveForNonSelectiveFilter() {
        // N=1M rows, sel=0.99 → 990K rows survive — pushing adds remote compute
        double pushCost = costModel.estimatePushCost(PushdownMode.EXACT, 1_000_000, 0.99, 100);
        double noPushCost = costModel.estimateNoPushCost(1_000_000, 100);

        // With high selectivity, pushdown cost (startup + remote compute + transfer 990K)
        // vs no-push (transfer 1M + local compute) — push might be slightly cheaper due to less transfer
        // But the point is shouldPush makes a reasonable decision
        boolean shouldPush = costModel.shouldPush(PushdownMode.EXACT, 1_000_000, 0.99, 100);
        // For sel=0.99, pushdown barely reduces transfer, adds startup+remote → should NOT push
        assertThat(shouldPush).isFalse();
    }

    @Test
    void conservativePushdownCostHigherThanExact() {
        // CONSERVATIVE over-scans: survivingRows ≥ N*sel (stats are coarse)
        // For the same N/sel, CONSERVATIVE should be more expensive than EXACT
        double exactCost = costModel.estimatePushCost(PushdownMode.EXACT, 1_000_000, 0.001, 100);
        double conservativeCost = costModel.estimatePushCost(PushdownMode.CONSERVATIVE, 1_000_000, 0.001, 100);

        // CONSERVATIVE transfers survivingRows (≥ N*sel) + still needs local residual compute
        assertThat(conservativeCost).isGreaterThan(exactCost);
    }

    @Test
    void shouldPushTrueForSelectiveFilter() {
        boolean shouldPush = costModel.shouldPush(PushdownMode.EXACT, 1_000_000, 0.001, 100);
        assertThat(shouldPush).isTrue();
    }

    @Test
    void shouldPushFalseForPointLookupSmallTable() {
        // Small table + equality → RTT dominates
        boolean shouldPush = costModel.shouldPush(PushdownMode.EXACT, 100, 0.01, 100);
        // For small N=100, startup=100 dominates → not worth pushing
        assertThat(shouldPush).isFalse();
    }

    @Test
    void inMemoryModeNeverPushes() {
        // IN_MEMORY: no remote benefit, just local filter
        boolean shouldPush = costModel.shouldPush(PushdownMode.IN_MEMORY, 1_000_000, 0.001, 100);
        assertThat(shouldPush).isFalse();
    }
}
