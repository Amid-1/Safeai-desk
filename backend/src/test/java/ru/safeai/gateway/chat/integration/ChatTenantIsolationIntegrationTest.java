package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.chat.service.ChatTurnReservationService;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
class ChatTenantIsolationIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatTurnReservationService reservationService;

    @Test
    void userCannotReadForeignTenantChat() {
        assertThatThrownBy(() -> chatService.findById(
                OTHER_CHAT_ID,
                principal(USER_ID, ORGANIZATION_ID, "ROLE_USER")
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void userCannotListForeignTenantMessagesByPathSubstitution() {
        assertThatThrownBy(() -> chatService.findMessages(
                OTHER_CHAT_ID,
                PageRequest.of(0, 50),
                principal(USER_ID, ORGANIZATION_ID, "ROLE_USER")
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void userCannotReserveTurnInForeignTenantChat() {
        assertThatThrownBy(() -> reservationService.reserveOrReplay(
                OTHER_CHAT_ID,
                new SendMessageRequest("Question", UUID.randomUUID()),
                principal(USER_ID, ORGANIZATION_ID, "ROLE_USER")
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void superAdminRoleDoesNotImplicitlyBypassPrivateChatOwnership() {
        assertThatThrownBy(() -> chatService.findById(
                CHAT_ID,
                principal(
                        UUID.fromString("00000000-0000-0000-0000-000000000101"),
                        PLATFORM_ORGANIZATION_ID,
                        "ROLE_SUPER_ADMIN"
                )
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    private SafeAiUserPrincipal principal(
            UUID userId,
            UUID organizationId,
            String role
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId,
                organizationId,
                "principal@test.example",
                0L,
                List.of(new SimpleGrantedAuthority(role))
        );
    }
}
