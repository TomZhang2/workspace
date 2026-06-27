package com.example.pushdown.shippability;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.expression.*;
import com.example.pushdown.session.SnapshotContext;
import com.example.pushdown.type.Type;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StableFunctionPinnerTest {
    @Test
    void replacesNowWithPinnedTimestamp() {
        Instant pinned = Instant.parse("2026-01-15T10:30:00Z");
        SnapshotContext snapshot = new SnapshotContext(pinned, SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

        FunctionSignature now = new FunctionSignature("NOW", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        Call nowCall = new Call(now, List.of(), Type.TIMESTAMP);

        ConnectorExpression result = new StableFunctionPinner().pinStableFunctions(nowCall, snapshot);

        assertThat(result).isInstanceOf(Constant.class);
        assertThat(((Constant) result).value()).isEqualTo(pinned);
        assertThat(((Constant) result).type()).isEqualTo(Type.TIMESTAMP);
    }

    @Test
    void replacesCurrentDateWithPinnedTimestamp() {
        Instant pinned = Instant.parse("2026-01-15T10:30:00Z");
        SnapshotContext snapshot = new SnapshotContext(pinned, SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

        FunctionSignature currentTs = new FunctionSignature("CURRENT_TIMESTAMP", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        Call call = new Call(currentTs, List.of(), Type.TIMESTAMP);

        ConnectorExpression result = new StableFunctionPinner().pinStableFunctions(call, snapshot);

        assertThat(result).isInstanceOf(Constant.class);
        assertThat(((Constant) result).value()).isEqualTo(pinned);
    }

    @Test
    void doesNotReplaceImmutableFunction() {
        Instant pinned = Instant.parse("2026-01-15T10:30:00Z");
        SnapshotContext snapshot = new SnapshotContext(pinned, SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

        FunctionSignature upper = new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        Variable nameCol = new Variable(new MockColumnHandle("name"), Type.VARCHAR);
        Call upperCall = new Call(upper, List.of(nameCol), Type.VARCHAR);

        ConnectorExpression result = new StableFunctionPinner().pinStableFunctions(upperCall, snapshot);

        // Should remain a Call (immutable, not replaced)
        assertThat(result).isInstanceOf(Call.class);
        assertThat(result).isEqualTo(upperCall);
    }

    @Test
    void pinsStableFunctionNestedInComparison() {
        Instant pinned = Instant.parse("2026-01-15T10:30:00Z");
        SnapshotContext snapshot = new SnapshotContext(pinned, SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

        FunctionSignature now = new FunctionSignature("NOW", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        Call nowCall = new Call(now, List.of(), Type.TIMESTAMP);
        Comparison cmp = new Comparison(Operator.LTE,
            new Variable(new MockColumnHandle("created_at"), Type.TIMESTAMP), nowCall);

        ConnectorExpression result = new StableFunctionPinner().pinStableFunctions(cmp, snapshot);

        assertThat(result).isInstanceOf(Comparison.class);
        Comparison resultCmp = (Comparison) result;
        assertThat(resultCmp.right()).isInstanceOf(Constant.class);
        assertThat(((Constant) resultCmp.right()).value()).isEqualTo(pinned);
    }
}
