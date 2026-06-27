package com.example.pushdown.planner;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.spi.PushdownConnector;
import java.util.ArrayList;
import java.util.List;

public class PushdownPathBuilder {

    private final PushdownConnector connector;

    public PushdownPathBuilder(PushdownConnector connector) {
        this.connector = connector;
    }

    public List<PlanPath> buildCandidates(ConnectorSession session,
                                            TableHandle table,
                                            ConnectorExpression predicate) {
        List<PlanPath> paths = new ArrayList<>();
        paths.add(PlanPath.localOnly(table));
        if (connector.isFilterPushable(session, table, predicate)) {
            paths.add(PlanPath.pushFilter(table));
        }
        return paths;
    }
}
