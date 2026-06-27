package com.example.pushdown.result;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import java.util.List;
import java.util.Optional;

public final class FilterResults {

    private FilterResults() {}

    public static ConjunctPushdown conjunct(
            ConnectorExpression original,
            Optional<ConnectorExpression> pushed,
            ConnectorExpression residual,
            PushdownMode mode) {
        return new ImmutableConjunctPushdown(original, pushed, residual, mode);
    }

    public static FilterResult of(TableHandle table, List<ConjunctPushdown> conjuncts) {
        return new ImmutableFilterResult(table, conjuncts);
    }

    private record ImmutableConjunctPushdown(
        ConnectorExpression originalConjunct,
        Optional<ConnectorExpression> pushedExpression,
        ConnectorExpression residualExpression,
        PushdownMode mode
    ) implements ConjunctPushdown {}

    private record ImmutableFilterResult(
        TableHandle pushedTable,
        List<ConjunctPushdown> conjunctResults
    ) implements FilterResult {}
}
