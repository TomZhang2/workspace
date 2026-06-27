package com.example.pushdown.planner;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.spi.PushdownConnector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds pushdown candidate {@link PlanPath}s for a table + predicate.
 *
 * <p>Phase 3 enhancements for path-explosion mitigation:
 * <ul>
 *   <li><b>Pruning</b> — a path is only added when the connector reports the
 *       corresponding operation as pushable (e.g. PUSH_FILTER is skipped when
 *       {@code isFilterPushable} returns false).</li>
 *   <li><b>Candidate cap</b> — at most {@link #maxCandidatesPerNode} paths are
 *       returned, sorted by ascending {@link PlanPath#estimatedCost()} so the
 *       cheapest survive.</li>
 *   <li><b>Memo</b> — the candidate list for a given table is cached so
 *       repeated planning of the same table avoids recomputation.</li>
 * </ul>
 */
public class PushdownPathBuilder {

    private static final int DEFAULT_MAX_CANDIDATES_PER_NODE = 5;

    private final PushdownConnector connector;
    private final int maxCandidatesPerNode;
    private final Map<TableHandle, List<PlanPath>> memo = new ConcurrentHashMap<>();

    public PushdownPathBuilder(PushdownConnector connector) {
        this(connector, DEFAULT_MAX_CANDIDATES_PER_NODE);
    }

    public PushdownPathBuilder(PushdownConnector connector, int maxCandidatesPerNode) {
        this.connector = connector;
        this.maxCandidatesPerNode = maxCandidatesPerNode;
    }

    public List<PlanPath> buildCandidates(ConnectorSession session,
                                            TableHandle table,
                                            ConnectorExpression predicate) {
        List<PlanPath> cached = memo.get(table);
        if (cached != null) {
            return cached;
        }
        List<PlanPath> paths = buildAndPrune(session, table, predicate);
        List<PlanPath> capped = capCandidates(paths);
        memo.put(table, capped);
        return capped;
    }

    private List<PlanPath> buildAndPrune(ConnectorSession session,
                                          TableHandle table,
                                          ConnectorExpression predicate) {
        List<PlanPath> paths = new ArrayList<>();
        // LOCAL_ONLY is always available — no pushdown, engine filters locally.
        paths.add(PlanPath.localOnly(table));
        // Pruning: only add PUSH_FILTER when the connector can actually push it.
        if (connector.isFilterPushable(session, table, predicate)) {
            paths.add(PlanPath.pushFilter(table));
        }
        return paths;
    }

    private List<PlanPath> capCandidates(List<PlanPath> paths) {
        if (paths.size() <= maxCandidatesPerNode) {
            return paths;
        }
        return paths.stream()
            .sorted(Comparator.comparingDouble(PlanPath::estimatedCost))
            .limit(maxCandidatesPerNode)
            .toList();
    }
}
