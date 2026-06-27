package com.example.pushdown.topn;

import com.example.pushdown.connector.mock.MockTableHandle;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LimitResultTest {
    @Test
    void limitResultDefaultNotGuaranteed() {
        LimitResult result = LimitResult.of(new MockTableHandle("users"), false);
        assertThat(result.limitGuaranteed()).isFalse();
    }

    @Test
    void limitResultGuaranteed() {
        LimitResult result = LimitResult.of(new MockTableHandle("users"), true);
        assertThat(result.limitGuaranteed()).isTrue();
    }
}
