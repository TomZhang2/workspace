package com.example.pushdown.deparse;

public interface SqlDeparser {
    DeparsedQuery deparseSelectStmt(PushedPlan pushedPlan, SqlDialect dialect);
}
