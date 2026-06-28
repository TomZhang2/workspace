package com.example.pushdown.security;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.session.ConnectorSession;
import java.util.Set;

/**
 * Masking-aware pushdown: masked columns are NOT pushed to source.
 * The engine fetches raw values and applies masking locally.
 */
public class MaskingAwarePushdown {

    private final MaskingService maskingService;

    public MaskingAwarePushdown(MaskingService maskingService) {
        this.maskingService = maskingService;
    }

    /**
     * Check if any projected columns are masked.
     * Returns a decision: push all, or push filter-only (exclude masked columns from projection).
     */
    public MaskingDecision checkMasking(TableHandle table,
                                         Set<ColumnHandle> projectedColumns,
                                         ConnectorSession session) {
        Set<ColumnHandle> maskedColumns = maskingService.getMaskedColumns(session.user(), table);
        
        boolean hasMasked = projectedColumns.stream().anyMatch(maskedColumns::contains);
        
        if (hasMasked) {
            // Masked columns present: can push filter, but NOT the masked column projections
            Set<ColumnHandle> safeColumns = Set.copyOf(
                projectedPointsMinusMasked(projectedColumns, maskedColumns)
            );
            return MaskingDecision.filterOnly(maskedColumns, safeColumns);
        }
        return MaskingDecision.pushAll();
    }

    private Set<ColumnHandle> projectedPointsMinusMasked(Set<ColumnHandle> projected, Set<ColumnHandle> masked) {
        var result = new java.util.HashSet<ColumnHandle>();
        for (ColumnHandle c : projected) {
            if (!masked.contains(c)) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * Service interface for masking policy retrieval.
     */
    public interface MaskingService {
        Set<ColumnHandle> getMaskedColumns(String user, TableHandle table);
    }

    /**
     * Decision: push all (no masking) or filter-only (masked columns excluded from projection).
     */
    public record MaskingDecision(boolean shouldPushAll, Set<ColumnHandle> maskedColumns,
                                   Set<ColumnHandle> safeColumns) {
        public static MaskingDecision pushAll() {
            return new MaskingDecision(true, Set.of(), Set.of());
        }

        public static MaskingDecision filterOnly(Set<ColumnHandle> masked, Set<ColumnHandle> safe) {
            return new MaskingDecision(false, masked, safe);
        }

        public boolean isPushAll() { return shouldPushAll; }
    }
}
