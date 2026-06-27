package com.example.pushdown.cost;

import com.example.pushdown.mode.PushdownMode;

/**
 * Cost model for pushdown decisions. Implements the Oracle F2 fix: different
 * cost formulas for {@link PushdownMode#EXACT} vs {@link PushdownMode#CONSERVATIVE}.
 *
 * <h2>EXACT vs CONSERVATIVE (Oracle F2 fix)</h2>
 * <p>In EXACT mode the source evaluates the predicate precisely and transfers
 * only the matching rows. In CONSERVATIVE mode the source can only prune files
 * using coarse statistics — it still scans every row in surviving files
 * (remote compute on N), transfers the over-scanned surviving rows, and the
 * engine must apply the exact predicate locally as a residual. CONSERVATIVE
 * therefore does strictly more work than EXACT for the same N/selectivity.
 *
 * <h2>shouldPush margin</h2>
 * <p>Pushdown is only worthwhile when it saves at least 1% of the no-push cost;
 * negligible savings do not justify the added code path / remote coupling.
 */
public class PushdownCostModel {

    private static final double PUSH_MARGIN = 0.01; // require >=1% savings
    private static final double LOCAL_COMPUTE_PER_ROW = 0.001;
    private static final double REMOTE_COMPUTE_PER_ROW = 0.001;
    private static final double CONSERVATIVE_OVERSCAN_FACTOR = 2.0;

    private final double sourceStartupCost;
    private final double sourceTupleCost;
    private final double remoteComputeRatio;
    private final double networkBandwidth;

    public PushdownCostModel(double sourceStartupCost, double sourceTupleCost,
                              double remoteComputeRatio, double networkBandwidth) {
        this.sourceStartupCost = sourceStartupCost;
        this.sourceTupleCost = sourceTupleCost;
        this.remoteComputeRatio = remoteComputeRatio;
        this.networkBandwidth = networkBandwidth;
    }

    /**
     * Estimate cost of NOT pushing: transfer all N rows + local compute.
     */
    public double estimateNoPushCost(long N, int rowSize) {
        double transferCost = transferCost(N, rowSize);
        double localCompute = N * LOCAL_COMPUTE_PER_ROW;
        return transferCost + localCompute;
    }

    /**
     * Estimate cost of pushing. DIFFERENT formulas for EXACT vs CONSERVATIVE
     * (Oracle F2 fix).
     */
    public double estimatePushCost(PushdownMode mode, long N, double selectivity, int rowSize) {
        return switch (mode) {
            case EXACT -> {
                // Source evaluates predicate, transfers only surviving rows.
                long outputRows = (long) (N * selectivity);
                double remoteCompute = N * REMOTE_COMPUTE_PER_ROW * remoteComputeRatio;
                yield sourceStartupCost + remoteCompute + transferCost(outputRows, rowSize);
            }
            case CONSERVATIVE -> {
                // Source skips files via stats, but surviving files are fully
                // scanned (remote compute on N — file pruning is coarse at the
                // row level). Surviving rows ≥ N*selectivity (over-scan factor
                // 2x for coarse stats). Engine still applies exact predicate
                // locally as a residual on the surviving rows.
                long survivingRows = (long) (N * selectivity * CONSERVATIVE_OVERSCAN_FACTOR);
                double filePruneCost = 1.0; // cheap manifest evaluation
                double remoteCompute = N * REMOTE_COMPUTE_PER_ROW * remoteComputeRatio;
                double localResidualCompute = survivingRows * LOCAL_COMPUTE_PER_ROW;
                yield sourceStartupCost + filePruneCost + remoteCompute
                    + transferCost(survivingRows, rowSize) + localResidualCompute;
            }
            case IN_MEMORY -> {
                // IN_MEMORY: no pushdown benefit, same as noPush (just different code path)
                yield estimateNoPushCost(N, rowSize) + sourceStartupCost; // worse than noPush
            }
        };
    }

    /**
     * Should we push? Compare push cost vs no-push cost, requiring a small
     * margin to avoid switching plans for negligible savings.
     */
    public boolean shouldPush(PushdownMode mode, long N, double selectivity, int rowSize) {
        if (mode == PushdownMode.IN_MEMORY) {
            return false; // IN_MEMORY never benefits from "pushing"
        }
        double pushCost = estimatePushCost(mode, N, selectivity, rowSize);
        double noPushCost = estimateNoPushCost(N, rowSize);
        return pushCost < noPushCost * (1.0 - PUSH_MARGIN);
    }

    private double transferCost(long rows, int rowSize) {
        double bytes = rows * (double) rowSize;
        return bytes / networkBandwidth + rows * sourceTupleCost;
    }
}
