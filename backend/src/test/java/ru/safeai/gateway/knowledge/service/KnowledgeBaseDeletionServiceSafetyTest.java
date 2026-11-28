package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeBaseDeletionServiceSafetyTest {
    @Test
    void disabledFeatureCannotEvenEnqueueDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditEventService audit = mock(AuditEventService.class);
        SafeAiUserPrincipal user = mock(SafeAiUserPrincipal.class);
        var service = new KnowledgeBaseDeletionService(jdbc, audit, false, false, true, true);
        assertThatThrownBy(() -> service.accept(UUID.randomUUID(), 0,
                "base-delete-request-12345", user))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(jdbc, audit);
    }
}
