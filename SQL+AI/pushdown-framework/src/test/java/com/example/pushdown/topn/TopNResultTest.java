package com.example.pushdown.topn;

import com.example.pushdown.connector.mock.MockTableHandle;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TopNResultTest {
    @Test
    void topNResultWithGuaranteedOrderAndCollation() {
        Collation collation = Collation.of("utf8mb4_general_ci");
        TopNResult result = TopNResult.of(
            new MockTableHandle("users"),
            true,   // orderGuaranteed
            true,   // limitGuaranteed
            Optional.of(collation)
        );
        
        assertThat(result.orderGuaranteed()).isTrue();
        assertThat(result.limitGuaranteed()).isTrue();
        assertThat(result.sortCollation()).contains(collation);
    }

    @Test
    void isOrderTrustworthyRequiresCollationMatch() {
        TopNResult result = TopNResult.of(
            new MockTableHandle("users"),
            true, true, Optional.of(Collation.of("utf8mb4_general_ci"))
        );
        
        // Match → trustworthy
        assertThat(result.isOrderTrustworthy(Collation.of("utf8mb4_general_ci"))).isTrue();
        // Mismatch → NOT trustworthy
        assertThat(result.isOrderTrustworthy(Collation.of("C"))).isFalse();
    }

    @Test
    void isOrderTrustworthyFalseWhenOrderNotGuaranteed() {
        TopNResult result = TopNResult.of(
            new MockTableHandle("users"),
            false, true, Optional.empty()
        );
        assertThat(result.isOrderTrustworthy(Collation.of("C"))).isFalse();
    }

    @Test
    void defaultSafetyWhenCollationMissing() {
        // orderGuaranteed=true but no collation → NOT trustworthy (safe default)
        TopNResult result = TopNResult.of(
            new MockTableHandle("users"),
            true, true, Optional.empty()
        );
        assertThat(result.isOrderTrustworthy(Collation.of("C"))).isFalse();
    }
}
