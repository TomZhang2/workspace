package com.example.pushdown.expression;

import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FunctionSignatureTest {
    @Test
    void signatureHasNameAndVolatility() {
        FunctionSignature upper = new FunctionSignature(
            "upper", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(upper.name()).isEqualTo("upper");
        assertThat(upper.volatility()).isEqualTo(FunctionVolatility.IMMUTABLE);
        assertThat(upper.parameterTypes()).containsExactly(Type.VARCHAR);
        assertThat(upper.returnType()).isEqualTo(Type.VARCHAR);
    }

    @Test
    void nowIsStable() {
        FunctionSignature now = new FunctionSignature(
            "now", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        assertThat(now.volatility()).isEqualTo(FunctionVolatility.STABLE);
    }

    @Test
    void randomIsVolatile() {
        FunctionSignature random = new FunctionSignature(
            "random", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        assertThat(random.volatility()).isEqualTo(FunctionVolatility.VOLATILE);
    }
}
