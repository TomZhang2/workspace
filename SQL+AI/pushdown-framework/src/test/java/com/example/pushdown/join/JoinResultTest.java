package com.example.pushdown.join;

import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.TableHandle;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JoinResultTest {
    @Test
    void fullPushJoinResult() {
        Comparison condition = new Comparison(Operator.EQ,
            new Variable(new com.example.pushdown.connector.mock.MockColumnHandle("a_id"), com.example.pushdown.type.Type.INTEGER),
            new Constant(1, com.example.pushdown.type.Type.INTEGER));
        
        JoinResult result = JoinResult.fullPush(
            new MockTableHandle("joined"),
            Expressions.TRUE()  // no residual condition
        );
        
        assertThat(result.pushedJoinTable()).contains(new MockTableHandle("joined"));
        assertThat(result.crossSourceStrategy()).isEqualTo(CrossSourceStrategy.FULL_PUSH);
        assertThat(result.remainingCondition()).isEqualTo(Expressions.TRUE());
    }

    @Test
    void filterEachSideJoinResult() {
        JoinResult result = JoinResult.filterEachSide(
            new MockTableHandle("left_filtered"),
            new MockTableHandle("right_filtered"),
            Expressions.TRUE()
        );
        
        assertThat(result.pushedJoinTable()).isEmpty();
        assertThat(result.pushedLeft()).contains(new MockTableHandle("left_filtered"));
        assertThat(result.pushedRight()).contains(new MockTableHandle("right_filtered"));
        assertThat(result.crossSourceStrategy()).isEqualTo(CrossSourceStrategy.FILTER_EACH_SIDE);
    }

    @Test
    void joinTypeEnumHasAllValues() {
        assertThat(JoinType.values())
            .contains(JoinType.INNER, JoinType.LEFT, JoinType.RIGHT, JoinType.FULL, JoinType.CROSS);
    }

    @Test
    void crossSourceStrategyEnumHasAllValues() {
        assertThat(CrossSourceStrategy.values())
            .contains(CrossSourceStrategy.FULL_PUSH, CrossSourceStrategy.FILTER_EACH_SIDE,
                       CrossSourceStrategy.BROADCAST_IN_LIST, CrossSourceStrategy.SEMI_JOIN);
    }
}
