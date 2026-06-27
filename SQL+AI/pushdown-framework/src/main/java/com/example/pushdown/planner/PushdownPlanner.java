package com.example.pushdown.planner;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.result.FilterResult;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;
import com.example.pushdown.spi.PushdownConnector;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class PushdownPlanner {

    private final PushdownConnector connector;
    private final PushdownPathBuilder pathBuilder;

    public PushdownPlanner(PushdownConnector connector) {
        this.connector = connector;
        this.pathBuilder = new PushdownPathBuilder(connector);
    }

    public Optional<FilterResult> planAndExecuteFilter(
            ConnectorSession session,
            TableHandle table,
            ConnectorExpression predicate,
            SnapshotContext snapshot) {

        List<PlanPath> candidates = pathBuilder.buildCandidates(session, table, predicate);

        PlanPath best = candidates.stream()
            .min(Comparator.comparingDouble(PlanPath::estimatedCost))
            .orElseThrow();

        if (!best.pushed()) {
            return Optional.empty();
        }

        return connector.applyFilter(session, table, predicate, snapshot);
    }
}
