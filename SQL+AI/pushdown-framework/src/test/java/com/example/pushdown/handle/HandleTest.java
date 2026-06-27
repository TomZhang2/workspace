package com.example.pushdown.handle;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HandleTest {
    @Test
    void columnHandleHasName() {
        ColumnHandle col = new TestColumnHandle("user_id");
        assertThat(col.name()).isEqualTo("user_id");
    }

    @Test
    void tableHandleHasName() {
        TableHandle table = new TestTableHandle("users");
        assertThat(table.name()).isEqualTo("users");
    }

    record TestColumnHandle(String name) implements ColumnHandle {}
    record TestTableHandle(String name) implements TableHandle {}
}
