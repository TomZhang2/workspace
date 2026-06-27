package com.example.pushdown.session;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {
    @Test
    void sessionHoldsUserAndQueryId() {
        ConnectorSession session = ConnectorSession.builder()
            .user("alice")
            .queryId("q-001")
            .serverId("mysql-prod")
            .build();
        assertThat(session.user()).isEqualTo("alice");
        assertThat(session.queryId()).isEqualTo("q-001");
        assertThat(session.serverId()).isEqualTo("mysql-prod");
    }

    @Test
    void snapshotContextHasTimestamp() {
        Instant now = Instant.now();
        SnapshotContext snapshot = new SnapshotContext(now, SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");
        assertThat(snapshot.queryTimestamp()).isEqualTo(now);
        assertThat(snapshot.isolationLevel()).isEqualTo(SnapshotContext.IsolationLevel.SNAPSHOT);
    }
}
