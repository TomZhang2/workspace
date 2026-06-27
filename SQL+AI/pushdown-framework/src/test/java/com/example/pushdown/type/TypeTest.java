package com.example.pushdown.type;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TypeTest {
    @Test
    void basicTypesHaveDistinctNames() {
        assertThat(Type.INTEGER.name()).isEqualTo("INTEGER");
        assertThat(Type.VARCHAR.name()).isEqualTo("VARCHAR");
        assertThat(Type.BOOLEAN.name()).isEqualTo("BOOLEAN");
    }

    @Test
    void integerTypeIsNotVarchar() {
        assertThat(Type.INTEGER).isNotEqualTo(Type.VARCHAR);
    }
}
