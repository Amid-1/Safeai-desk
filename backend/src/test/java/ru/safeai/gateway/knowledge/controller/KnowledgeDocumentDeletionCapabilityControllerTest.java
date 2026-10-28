package ru.safeai.gateway.knowledge.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.service.KnowledgeAccessService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KnowledgeDocumentDeletionCapabilityControllerTest {

    private static final UUID BASE = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final SafeAiUserPrincipal USER = SafeAiUserPrincipal.accessTokenPrincipal(
            UUID.randomUUID(), ORG, 0, 0,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @Test
    void bothFlagsOffReturnsSafeFalseAndStillChecksOwner() {
        var access = mock(KnowledgeAccessService.class);
        var controller = new KnowledgeDocumentDeletionCapabilityController(access, false, false);
        assertThat(controller.capability(BASE, USER))
                .isEqualTo(new KnowledgeDocumentDeletionCapabilityController.CapabilityResponse(
                        false, "FEATURE_DISABLED"));
        verify(access).requireAccess(BASE, USER, KnowledgeBaseAccessLevel.OWNER);
    }

    @Test
    void oneFlagIsNotEnough() {
        var access = mock(KnowledgeAccessService.class);
        assertThat(new KnowledgeDocumentDeletionCapabilityController(access, true, false)
                .capability(BASE, USER).allowed()).isFalse();
        assertThat(new KnowledgeDocumentDeletionCapabilityController(access, false, true)
                .capability(BASE, USER).allowed()).isFalse();
    }

    @Test
    void bothFlagsOnAndOwnerCanDelete() {
        var access = mock(KnowledgeAccessService.class);
        var response = new KnowledgeDocumentDeletionCapabilityController(access, true, true)
                .capability(BASE, USER);
        assertThat(response.allowed()).isTrue();
        assertThat(response.reason()).isEqualTo("ENABLED");
        verify(access).requireAccess(BASE, USER, KnowledgeBaseAccessLevel.OWNER);
    }

    @Test
    void nonOwnerIsDeniedEvenWhenFeatureIsDisabled() {
        var access = mock(KnowledgeAccessService.class);
        when(access.requireAccess(BASE, USER, KnowledgeBaseAccessLevel.OWNER))
                .thenThrow(new ForbiddenOperationException("Недостаточно прав."));
        var controller = new KnowledgeDocumentDeletionCapabilityController(access, false, false);
        assertThatThrownBy(() -> controller.capability(BASE, USER))
                .isInstanceOf(ForbiddenOperationException.class);
    }
}
