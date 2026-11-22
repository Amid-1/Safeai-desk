// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/retention/RetentionManagementServiceTest.java
package ru.safeai.gateway.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.service.ChatErasureWorker;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionExecutor;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionExecutor;
import ru.safeai.gateway.user.service.UserManagementProperties;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RetentionManagementServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private static final UUID ORGANIZATION_ID = PlatformProperties.DEFAULT_PLATFORM_ORGANIZATION_ID;
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    // SQL strings here are mock expectations, not statements executed by this unit test.
    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    private static final String POLICY_SELECT = """
            SELECT chat_allowed, knowledge_allowed, version FROM public.platform_erasure_policy_v102
            WHERE singleton
            """;
    private static final String POLICY_LOCK = POLICY_SELECT + " FOR UPDATE";
    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    private static final String POLICY_UPDATE = """
            UPDATE public.platform_erasure_policy_v102
            SET chat_allowed=?, knowledge_allowed=?, version=version+1 WHERE singleton AND version=?
            """;
    private final SafeAiUserPrincipal actor = mock(SafeAiUserPrincipal.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final ObjectProvider<ChatErasureWorker> chatWorker = mockProvider();
    private final ObjectProvider<KnowledgeDocumentDeletionExecutor> documentWorker = mockProvider();
    private final ObjectProvider<KnowledgeBaseDeletionExecutor> baseWorker = mockProvider();
    private final RetentionManagementService service = new RetentionManagementService(jdbc, environment,
            new PlatformProperties(ORGANIZATION_ID), audit, new UserManagementProperties(null),
            chatWorker, documentWorker, baseWorker);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider() {
        return mock(ObjectProvider.class);
    }

    @BeforeEach
    void identity() {
        when(actor.getOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(actor.getId()).thenReturn(USER_ID);
        stubRoles(List.of("SUPER_ADMIN"));
    }

    @Test
    void tenantCannotReadPlatformPolicy() {
        when(actor.getOrganizationId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.status(actor)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, false, 4L), actor))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void revokedRoleCannotReadPlatformPolicy() {
        stubRoles(List.of("ADMIN"));
        assertThatThrownBy(() -> service.status(actor)).isInstanceOf(ForbiddenOperationException.class);
        verifyNoPolicyAccess();
        verifyNoInteractions(audit);
    }

    @Test
    void refusesAmbiguousRolesAndMissingPrincipal() {
        assertThatThrownBy(() -> service.status(null)).isInstanceOf(ForbiddenOperationException.class);
        stubRoles(List.of("ADMIN", "SUPER_ADMIN"));
        assertThatThrownBy(() -> service.status(actor)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, false, 4L), actor))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoPolicyAccess();
        verifyNoInteractions(audit);
    }

    @Test
    void baseWorkerCannotSubstituteForDocumentWorker() {
        enableKnowledgeConfiguration();
        when(baseWorker.getIfAvailable()).thenReturn(mock(KnowledgeBaseDeletionExecutor.class));
        when(jdbc.queryForObject(anyString(), ArgumentMatchers
                .<RowMapper<RetentionManagementService.Policy>>any()))
                .thenReturn(new RetentionManagementService.Policy(false, false, 4));
        var state = service.status(actor);
        assertThat(state.base().configured()).isTrue();
        assertThat(state.base().workerInitialized()).isFalse();
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, true, 4L), actor))
                .isInstanceOf(ConflictException.class);
        verifyNoPolicyWrite();
        verifyNoInteractions(audit);
    }

    @Test
    void cannotResumeWithoutWorkerOrOverwriteNewVersion() {
        when(jdbc.queryForObject(eq(POLICY_LOCK), ArgumentMatchers
                .<RowMapper<RetentionManagementService.Policy>>any()))
                .thenReturn(new RetentionManagementService.Policy(false, false, 4));
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(true, false, 4L), actor))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, false, 3L), actor))
                .isInstanceOf(ConflictException.class);
        verifyNoPolicyWrite();
        verifyNoInteractions(audit);
    }

    @Test
    void pauseUpdatesThenAuditsBeforeReturningStatus() {
        stubPolicy(true, true, true, 4);
        stubPolicy(false, false, true, 5);
        when(jdbc.update(eq(POLICY_UPDATE), eq(false), eq(true), eq(4L))).thenReturn(1);
        var result = service.update(new RetentionManagementService.Change(false, true, 4L), actor);
        assertThat(result.chatAllowed()).isFalse();
        assertThat(result.version()).isEqualTo(5);
        var order = inOrder(jdbc, audit);
        order.verify(jdbc).query(contains("FOR SHARE OF o,u,ur"), ArgumentMatchers.<RowMapper<String>>any(),
                eq(ORGANIZATION_ID), eq(USER_ID));
        order.verify(jdbc).queryForObject(eq(POLICY_LOCK), ArgumentMatchers.<RowMapper<RetentionManagementService.Policy>>any());
        order.verify(jdbc).update(eq(POLICY_UPDATE), eq(false), eq(true), eq(4L));
        order.verify(audit).recordPrincipalSnapshot(eq(actor), eq(ORGANIZATION_ID),
                eq(AuditEventType.RETENTION_POLICY_CHANGED), any(AuditDetails.class));
        order.verify(jdbc).queryForObject(eq(POLICY_SELECT), ArgumentMatchers.<RowMapper<RetentionManagementService.Policy>>any());
        order.verifyNoMoreInteractions();
        assertAudit(true, true, false, true);
    }

    @Test
    void staleVersionRejectedBeforeWorkerInspection() {
        stubPolicy(true, false, false, 4);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, false, 3L), actor))
                .isInstanceOf(ConflictException.class);
        verifyNoPolicyWrite();
        verifyNoInteractions(audit, chatWorker, documentWorker, baseWorker);
    }

    @Test
    void missingPersistedRoleCannotChangePolicy() {
        stubRoles(List.of());
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, false, 4L), actor))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(audit, chatWorker, documentWorker, baseWorker);
        verifyNoPolicyAccess();
    }

    @Test
    void invalidChangeFailsBeforePolicyAccess() {
        assertThatThrownBy(() -> service.update(null, actor)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(null, true, 4L), actor))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(true, null, 4L), actor))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(true, true, null), actor))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(true, true, -1L), actor))
                .isInstanceOf(BadRequestException.class);
        verifyNoPolicyAccess();
        verifyNoInteractions(audit, chatWorker, documentWorker, baseWorker);
    }

    @Test
    void statusDistinguishesAllowedPolicyFromInfrastructureReadiness() {
        stubPolicy(false, true, true, 4);
        var state = service.status(actor);
        assertThat(state.chatAllowed()).isTrue();
        assertThat(state.knowledgeAllowed()).isTrue();
        assertThat(state.version()).isEqualTo(4L);
        assertThat(state.userRetentionSeconds()).isEqualTo(604800L);
        assertThat(state.chat()).isEqualTo(new RetentionManagementService.Gate(false, false));
        assertThat(state.document()).isEqualTo(new RetentionManagementService.Gate(false, false));
        assertThat(state.base()).isEqualTo(new RetentionManagementService.Gate(false, false));
        verifyNoInteractions(audit);
    }

    @Test
    void unchangedPolicyDoesNotWriteOrAudit() {
        stubPolicy(true, true, true, 4);
        var state = service.update(new RetentionManagementService.Change(true, true, 4L), actor);
        assertThat(state.version()).isEqualTo(4L);
        verifyNoPolicyWrite();
        verifyNoInteractions(audit);
    }

    @Test
    void resumesChatOnlyWithConfiguredAndInitializedWorker() {
        enableChatConfiguration();
        when(chatWorker.getIfAvailable()).thenReturn(mock(ChatErasureWorker.class));
        stubPolicy(true, false, false, 4);
        stubPolicy(false, true, false, 5);
        when(jdbc.update(eq(POLICY_UPDATE), eq(true), eq(false), eq(4L))).thenReturn(1);
        var state = service.update(new RetentionManagementService.Change(true, false, 4L), actor);
        assertThat(state.chatAllowed()).isTrue();
        assertThat(state.chat().unavailable()).isFalse();
        assertThat(state.version()).isEqualTo(5L);
        assertAudit(false, false, true, false);
    }

    @Test
    void initializedChatWorkerDoesNotOverrideMissingRetentionApproval() {
        enableChatConfiguration();
        environment.setProperty("safeai.chat.erasure.retention-approved", "false");
        when(chatWorker.getIfAvailable()).thenReturn(mock(ChatErasureWorker.class));
        stubPolicy(true, false, false, 4);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(true, false, 4L), actor))
                .isInstanceOf(ConflictException.class);
        verifyNoPolicyWrite();
        verifyNoInteractions(audit);
    }

    @Test
    void resumesKnowledgeForDocumentsWithoutBaseWorker() {
        enableKnowledgeConfiguration();
        when(documentWorker.getIfAvailable()).thenReturn(mock(KnowledgeDocumentDeletionExecutor.class));
        stubPolicy(true, false, false, 4);
        stubPolicy(false, false, true, 5);
        when(jdbc.update(eq(POLICY_UPDATE), eq(false), eq(true), eq(4L))).thenReturn(1);
        var state = service.update(new RetentionManagementService.Change(false, true, 4L), actor);
        assertThat(state.knowledgeAllowed()).isTrue();
        assertThat(state.document().unavailable()).isFalse();
        assertThat(state.base().configured()).isTrue();
        assertThat(state.base().workerInitialized()).isFalse();
        assertAudit(false, false, false, true);
    }

    @Test
    void baseReadinessRequiresBothKnowledgeWorkers() {
        enableKnowledgeConfiguration();
        when(documentWorker.getIfAvailable()).thenReturn(mock(KnowledgeDocumentDeletionExecutor.class));
        when(baseWorker.getIfAvailable()).thenReturn(mock(KnowledgeBaseDeletionExecutor.class));
        stubPolicy(false, false, true, 4);
        var state = service.status(actor);
        assertThat(state.document().unavailable()).isFalse();
        assertThat(state.base().unavailable()).isFalse();
        verifyNoInteractions(audit);
    }

    @Test
    void knowledgeWorkerDoesNotOverrideMissingObjectLock() {
        enableKnowledgeConfiguration();
        environment.setProperty("safeai.knowledge.erasure-journal.object-lock-required", "false");
        when(documentWorker.getIfAvailable()).thenReturn(mock(KnowledgeDocumentDeletionExecutor.class));
        stubPolicy(true, false, false, 4);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, true, 4L), actor))
                .isInstanceOf(ConflictException.class);
        verifyNoPolicyWrite();
        verifyNoInteractions(audit);
    }

    @Test
    void failedCompareAndSetIsConflictWithoutAudit() {
        stubPolicy(true, true, true, 4);
        when(jdbc.update(eq(POLICY_UPDATE), eq(false), eq(true), eq(4L))).thenReturn(0);
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, true, 4L), actor))
                .isInstanceOf(ConflictException.class);
        verify(jdbc).update(eq(POLICY_UPDATE), eq(false), eq(true), eq(4L));
        verify(jdbc, never()).queryForObject(eq(POLICY_SELECT), ArgumentMatchers.<RowMapper<RetentionManagementService.Policy>>any());
        verifyNoInteractions(audit);
    }

    @Test
    void auditFailurePropagatesInsteadOfReturningSuccess() {
        stubPolicy(true, true, true, 4);
        when(jdbc.update(eq(POLICY_UPDATE), eq(false), eq(true), eq(4L))).thenReturn(1);
        var failure = new IllegalStateException("audit unavailable");
        doThrow(failure).when(audit).recordPrincipalSnapshot(eq(actor), eq(ORGANIZATION_ID),
                eq(AuditEventType.RETENTION_POLICY_CHANGED), any(AuditDetails.class));
        assertThatThrownBy(() -> service.update(new RetentionManagementService.Change(false, true, 4L), actor))
                .isSameAs(failure);
        verify(jdbc, never()).queryForObject(eq(POLICY_SELECT), ArgumentMatchers.<RowMapper<RetentionManagementService.Policy>>any());
    }

    private void stubRoles(List<String> roles) {
        when(jdbc.query(contains("FOR SHARE OF o,u,ur"), ArgumentMatchers.<RowMapper<String>>any(),
                eq(ORGANIZATION_ID), eq(USER_ID))).thenReturn(roles);
    }

    private void stubPolicy(boolean lock, boolean chat, boolean knowledge, long version) {
        when(jdbc.queryForObject(eq(lock ? POLICY_LOCK : POLICY_SELECT),
                ArgumentMatchers.<RowMapper<RetentionManagementService.Policy>>any()))
                .thenReturn(new RetentionManagementService.Policy(chat, knowledge, version));
    }

    private void verifyNoPolicyAccess() {
        verify(jdbc, never()).queryForObject(anyString(), ArgumentMatchers.<RowMapper<RetentionManagementService.Policy>>any());
        verifyNoPolicyWrite();
    }

    private void verifyNoPolicyWrite() {
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private void assertAudit(boolean oldChat, boolean oldKnowledge, boolean chat, boolean knowledge) {
        var details = ArgumentCaptor.forClass(AuditDetails.class);
        verify(audit).recordPrincipalSnapshot(eq(actor), eq(ORGANIZATION_ID),
                eq(AuditEventType.RETENTION_POLICY_CHANGED), details.capture());
        assertThat(details.getValue().toMap()).isEqualTo(Map.of(
                "previousChatAllowed", oldChat, "previousKnowledgeAllowed", oldKnowledge,
                "chatAllowed", chat, "knowledgeAllowed", knowledge, "version", 5L));
        verifyNoMoreInteractions(audit);
    }

    private void enableChatConfiguration() {
        for (String key : List.of("accept-enabled", "executor-enabled", "retention-approved")) {
            environment.setProperty("safeai.chat.erasure." + key, "true");
        }
    }

    private void enableKnowledgeConfiguration() {
        for (String key : List.of("deletion.accept-enabled", "deletion.executor-enabled",
                "erasure-journal.enabled", "erasure-journal.object-lock-required",
                "base-deletion.accept-enabled", "base-deletion.executor-enabled")) {
            environment.setProperty("safeai.knowledge." + key, "true");
        }
    }
}
