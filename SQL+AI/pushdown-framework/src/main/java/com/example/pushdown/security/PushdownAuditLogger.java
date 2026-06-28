package com.example.pushdown.security;

import com.example.pushdown.deparse.DeparsedQuery;
import com.example.pushdown.session.ConnectorSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Audit logger: records all pushed SQL with user identity for audit trail.
 */
public class PushdownAuditLogger {

    private final List<AuditEntry> entries = new ArrayList<>();
    private final boolean enabled;

    public PushdownAuditLogger(boolean enabled) {
        this.enabled = enabled;
    }

    public void logPushdown(String userId, String sourceId, DeparsedQuery query,
                             ConnectorSession session) {
        if (!enabled) return;
        entries.add(new AuditEntry(
            Instant.now(),
            userId,
            sourceId,
            query.sql(),
            session.queryId()
        ));
    }

    public List<AuditEntry> getEntries() {
        return List.copyOf(entries);
    }

    public void clear() {
        entries.clear();
    }

    public record AuditEntry(
        Instant timestamp,
        String userId,
        String sourceId,
        String sql,
        String queryId
    ) {}
}
