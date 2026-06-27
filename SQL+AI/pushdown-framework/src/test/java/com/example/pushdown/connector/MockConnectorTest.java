package com.example.pushdown.connector;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.spi.*;
import com.example.pushdown.type.Type;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockConnectorTest {
    @Test
    void mockConnectorDeclaresFilterPushable() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = session();
        MockTableHandle table = new MockTableHandle("users");
        MockColumnHandle col = new MockColumnHandle("age");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(col, Type.INTEGER), new Constant(18, Type.INTEGER));

        assertThat(connector.isFilterPushable(session, table, pred)).isTrue();
    }

    @Test
    void mockConnectorAppliesFilterAsInMemory() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = session();
        MockTableHandle table = new MockTableHandle("users");
        MockColumnHandle col = new MockColumnHandle("age");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(col, Type.INTEGER), new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = connector.applyFilter(session, table, pred, null);
        assertThat(result).isPresent();

        FilterResult fr = result.get();
        assertThat(fr.conjunctResults()).hasSize(1);
        assertThat(fr.conjunctResults().get(0).mode()).isEqualTo(PushdownMode.IN_MEMORY);
        assertThat(fr.conjunctResults().get(0).pushedExpression()).isEmpty();
        assertThat(fr.conjunctResults().get(0).residualExpression()).isEqualTo(pred);
    }

    @Test
    void mockConnectorSupportsFallback() {
        MockConnector connector = new MockConnector();
        assertThat(connector.supportsFallback()).isTrue();
    }

    @Test
    void mockConnectorFallbackReturnsSameTable() {
        MockConnector connector = new MockConnector();
        MockTableHandle table = new MockTableHandle("users");
        TableHandle fallback = connector.fallbackToFullScan(table);
        assertThat(fallback).isEqualTo(table);
    }

    private ConnectorSession session() {
        return ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
    }
}
