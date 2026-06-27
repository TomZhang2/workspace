package com.example.pushdown.deparse;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeparsedQueryTest {
    @Test
    void deparsedQueryHoldsAllFields() {
        DeparsedQuery query = DeparsedQuery.of(
            "SELECT `age` FROM `users` WHERE `age` = 18",
            List.of(1),
            List.of(),
            100
        );
        assertThat(query.sql()).isEqualTo("SELECT `age` FROM `users` WHERE `age` = 18");
        assertThat(query.retrievedAttrs()).containsExactly(1);
        assertThat(query.parameters()).isEmpty();
        assertThat(query.fetchSize()).isEqualTo(100);
    }
}
