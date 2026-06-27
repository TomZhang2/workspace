package com.example.pushdown.session;

import java.time.Instant;

public record SnapshotContext(
    Instant queryTimestamp,
    IsolationLevel isolationLevel,
    String snapshotId
) {
    public enum IsolationLevel {
        SNAPSHOT,
        REPEATABLE_READ,
        READ_COMMITTED
    }
}
