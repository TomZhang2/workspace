package com.example.pushdown.mode;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PushdownModeTest {
    @Test
    void threeModesExist() {
        assertThat(PushdownMode.values())
            .containsExactlyInAnyOrder(
                PushdownMode.EXACT,
                PushdownMode.CONSERVATIVE,
                PushdownMode.IN_MEMORY);
    }
}
