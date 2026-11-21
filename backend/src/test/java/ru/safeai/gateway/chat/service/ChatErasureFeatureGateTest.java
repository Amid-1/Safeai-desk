package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.safeai.gateway.chat.dto.ChatErasureRequest;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** No database write or AI call may occur while destructive controls are off. */
class ChatErasureFeatureGateTest {
    @Test
    void erasureIsOffByDefaultAndDoesNotTouchDependencies() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new ChatLifecycleService(
                mock(ru.safeai.gateway.chat.repository.ChatSessionRepository.class),
                mock(ru.safeai.gateway.chat.repository.ChatMessageRepository.class),
                mock(ru.safeai.gateway.chat.repository.ChatTurnMutexRepository.class),
                mock(ChatMapper.class),
                mock(ru.safeai.gateway.chat.config.ChatProperties.class),
                mock(ru.safeai.gateway.audit.service.AuditEventService.class),
                jdbc, java.time.Clock.systemUTC(), false, false, false);
        var principal = mock(SafeAiUserPrincipal.class);
        // Authorization context is valid: only the feature gate may reject.
        when(principal.getId()).thenReturn(UUID.randomUUID());
        when(principal.getOrganizationId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.acceptErasure(
                UUID.randomUUID(), 0, "erase:strong-key-123",
                new ChatErasureRequest("Archived", "УДАЛИТЬ НАВСЕГДА"), principal))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void workerRefusesApplicationDatabaseCredentials() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(jdbc.queryForObject("SELECT session_user", String.class)).thenReturn("safeai_app");
        assertThatThrownBy(() -> new ChatErasureWorker(jdbc, manager))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dedicated DB login");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void workerFailureDiagnosticsDoNotExposeSqlOrSensitiveContent() {
        var sqlFailure = new java.sql.SQLException("PRIVATE PROMPT and sensitive SQL", "42501");
        var wrapped = new IllegalStateException("PRIVATE PROMPT", sqlFailure);
        org.assertj.core.api.Assertions.assertThat(ChatErasureWorker.safeFailureCode(wrapped))
                .isEqualTo("SQLSTATE_42501");
        org.assertj.core.api.Assertions.assertThat(ChatErasureWorker.safeFailureCode(
                new IllegalStateException("PRIVATE PROMPT")))
                .isEqualTo("ERASURE_EXECUTION_FAILED");
        org.assertj.core.api.Assertions.assertThat(ChatErasureWorker.safeFailureCode(
                new java.sql.SQLException("PRIVATE PROMPT", "Z9*1X")))
                .isEqualTo("ERASURE_EXECUTION_FAILED");
    }
}
