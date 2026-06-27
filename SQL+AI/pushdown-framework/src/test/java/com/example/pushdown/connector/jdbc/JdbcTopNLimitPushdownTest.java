package com.example.pushdown.connector.jdbc;

import com.example.pushdown.deparse.SortItem;
import com.example.pushdown.expression.*;
import com.example.pushdown.session.*;
import com.example.pushdown.topn.*;
import com.example.pushdown.type.Type;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JdbcTopNLimitPushdownTest {
    private final JdbcConnector connector = new JdbcConnector("mysql-prod");
    private final ConnectorSession session = ConnectorSession.builder()
        .user("test").queryId("q1").serverId("mysql-prod").build();
    private final SnapshotContext snapshot = new SnapshotContext(
        Instant.parse("2026-01-15T10:30:00Z"), SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

    @Test
    void isTopNPushable() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        SortItem sort = new SortItem(
            new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
            SortItem.SortDirection.DESC, SortItem.NullOrdering.LAST);
        
        assertThat(connector.isTopNPushable(session, table, 10, List.of(sort))).isTrue();
    }

    @Test
    void applyTopNReturnsGuaranteedResult() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        SortItem sort = new SortItem(
            new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
            SortItem.SortDirection.DESC, SortItem.NullOrdering.LAST);
        
        Optional<TopNResult> result = connector.applyTopN(session, table, 10, List.of(sort));
        
        assertThat(result).isPresent();
        assertThat(result.get().orderGuaranteed()).isTrue();
        assertThat(result.get().limitGuaranteed()).isTrue();
        assertThat(result.get().sortCollation()).isPresent();
    }

    @Test
    void isLimitPushable() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        assertThat(connector.isLimitPushable(session, table, 100)).isTrue();
    }

    @Test
    void applyLimitReturnsGuaranteedResult() {
        JdbcTableHandle table = new JdbcTableHandle("users");
        Optional<LimitResult> result = connector.applyLimit(session, table, 100);
        
        assertThat(result).isPresent();
        assertThat(result.get().limitGuaranteed()).isTrue();
    }

    @Test
    void topNWithDeparseGeneratesOrderByLimit() {
        // Verify the full pipeline: TopN pushdown → PushedPlan → deparse → SQL with ORDER BY + LIMIT
        JdbcTableHandle table = new JdbcTableHandle("users");
        SortItem sort = new SortItem(
            new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
            SortItem.SortDirection.DESC, SortItem.NullOrdering.LAST);
        
        Optional<TopNResult> topNResult = connector.applyTopN(session, table, 10, List.of(sort));
        assertThat(topNResult).isPresent();
        
        // Build a PushedPlan with sort + limit and deparse
        com.example.pushdown.deparse.PushedPlan plan = com.example.pushdown.deparse.PushedPlan.builder()
            .tableHandle(table)
            .projections(List.of(new Variable(new JdbcColumnHandle("name"), Type.VARCHAR)))
            .conjunctResults(List.of())
            .sortItems(Optional.of(List.of(sort)))
            .limit(Optional.of(10L))
            .build();
        
        com.example.pushdown.deparse.DeparsedQuery query = 
            new com.example.pushdown.deparse.DefaultSqlDeparser()
                .deparseSelectStmt(plan, com.example.pushdown.deparse.SqlDialect.MYSQL);
        
        assertThat(query.sql()).contains("ORDER BY");
        assertThat(query.sql()).contains("`age` DESC");
        assertThat(query.sql()).contains("NULLS LAST");
        assertThat(query.sql()).contains("LIMIT 10");
    }
}
