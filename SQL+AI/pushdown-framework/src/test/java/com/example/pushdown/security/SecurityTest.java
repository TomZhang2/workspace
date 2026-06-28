package com.example.pushdown.security;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.deparse.DeparsedQuery;
import com.example.pushdown.expression.*;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityTest {
    @Test
    void rlsAppendsPredicateToUserPredicate() {
        Comparison userPred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER), new Constant(18, Type.INTEGER));
        Comparison rlsPred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("tenant_id"), Type.INTEGER), new Constant(42, Type.INTEGER));
        
        RlsAwarePushdown.RlsService rlsService = (user, table) -> List.of(rlsPred);
        RlsAwarePushdown rls = new RlsAwarePushdown(rlsService);
        
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q1").serverId("s1").build();
        
        ConnectorExpression result = rls.appendRlsPredicates(userPred, session, new MockTableHandle("users"));
        
        assertThat(result).isInstanceOf(Logical.class);
        Logical logical = (Logical) result;
        assertThat(logical.op()).isEqualTo(LogicalOperator.AND);
        assertThat(logical.terms()).hasSize(2);
    }

    @Test
    void rlsNoPredicatesReturnsOriginal() {
        Comparison userPred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER), new Constant(18, Type.INTEGER));
        
        RlsAwarePushdown.RlsService rlsService = (user, table) -> List.of();
        RlsAwarePushdown rls = new RlsAwarePushdown(rlsService);
        
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q1").serverId("s1").build();
        
        ConnectorExpression result = rls.appendRlsPredicates(userPred, session, new MockTableHandle("users"));
        assertThat(result).isEqualTo(userPred);
    }

    @Test
    void maskingDetectsMaskedColumns() {
        MockColumnHandle ssn = new MockColumnHandle("ssn");
        MockColumnHandle name = new MockColumnHandle("name");
        
        MaskingAwarePushdown.MaskingService maskingService = (user, table) -> Set.of(ssn);
        MaskingAwarePushdown masking = new MaskingAwarePushdown(maskingService);
        
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q1").serverId("s1").build();
        
        MaskingAwarePushdown.MaskingDecision decision = masking.checkMasking(
            new MockTableHandle("users"), Set.of(ssn, name), session);
        
        assertThat(decision.isPushAll()).isFalse();
        assertThat(decision.maskedColumns()).contains(ssn);
        assertThat(decision.safeColumns()).contains(name);
        assertThat(decision.safeColumns()).doesNotContain(ssn);
    }

    @Test
    void maskingNoMaskedColumnsPushAll() {
        MockColumnHandle name = new MockColumnHandle("name");
        MockColumnHandle age = new MockColumnHandle("age");
        
        MaskingAwarePushdown.MaskingService maskingService = (user, table) -> Set.of();
        MaskingAwarePushdown masking = new MaskingAwarePushdown(maskingService);
        
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q1").serverId("s1").build();
        
        MaskingAwarePushdown.MaskingDecision decision = masking.checkMasking(
            new MockTableHandle("users"), Set.of(name, age), session);
        
        assertThat(decision.isPushAll()).isTrue();
    }

    @Test
    void auditLoggerRecordsPushedSql() {
        PushdownAuditLogger logger = new PushdownAuditLogger(true);
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q-001").serverId("mysql-prod").build();
        
        DeparsedQuery query = DeparsedQuery.of(
            "SELECT `name` FROM `users` WHERE `age` = 18",
            List.of(1), List.of(), 100);
        
        logger.logPushdown("alice", "mysql-prod", query, session);
        
        assertThat(logger.getEntries()).hasSize(1);
        PushdownAuditLogger.AuditEntry entry = logger.getEntries().get(0);
        assertThat(entry.userId()).isEqualTo("alice");
        assertThat(entry.sourceId()).isEqualTo("mysql-prod");
        assertThat(entry.sql()).contains("SELECT");
        assertThat(entry.queryId()).isEqualTo("q-001");
    }

    @Test
    void auditLoggerDisabledDoesNotRecord() {
        PushdownAuditLogger logger = new PushdownAuditLogger(false);
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q1").serverId("s1").build();
        
        DeparsedQuery query = DeparsedQuery.of("SELECT 1", List.of(), List.of(), 100);
        logger.logPushdown("alice", "s1", query, session);
        
        assertThat(logger.getEntries()).isEmpty();
    }
}
