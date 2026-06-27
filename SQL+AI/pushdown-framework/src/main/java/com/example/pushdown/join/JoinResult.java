package com.example.pushdown.join;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import java.util.Optional;

public interface JoinResult {
    Optional<TableHandle> pushedJoinTable();
    Optional<TableHandle> pushedLeft();
    Optional<TableHandle> pushedRight();
    ConnectorExpression remainingCondition();
    CrossSourceStrategy crossSourceStrategy();

    static JoinResult fullPush(TableHandle joined, ConnectorExpression residual) {
        return new ImmutableJoinResult(Optional.of(joined), Optional.empty(), Optional.empty(),
            residual, CrossSourceStrategy.FULL_PUSH);
    }

    static JoinResult filterEachSide(TableHandle left, TableHandle right, ConnectorExpression residual) {
        return new ImmutableJoinResult(Optional.empty(), Optional.of(left), Optional.of(right),
            residual, CrossSourceStrategy.FILTER_EACH_SIDE);
    }

    record ImmutableJoinResult(
        Optional<TableHandle> pushedJoinTable,
        Optional<TableHandle> pushedLeft,
        Optional<TableHandle> pushedRight,
        ConnectorExpression remainingCondition,
        CrossSourceStrategy crossSourceStrategy
    ) implements JoinResult {}
}
