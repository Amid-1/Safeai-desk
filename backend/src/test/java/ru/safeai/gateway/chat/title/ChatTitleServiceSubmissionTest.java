package ru.safeai.gateway.chat.title;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.dto.AutomaticChatTitleRequest;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.chat.repository.ChatSessionRepository;
import ru.safeai.gateway.chat.repository.ChatTurnMutexRepository;
import ru.safeai.gateway.chat.service.ChatMapper;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatTitleServiceSubmissionTest {
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatTurnMutexRepository mutex = mock(ChatTurnMutexRepository.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private final ChatMapper mapper = mock(ChatMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
    private final ChatTitleService service = new ChatTitleService(sessions, mutex, audit, mapper, clock);

    @Test
    void submittedTextSetsTitleWithoutAnyAiProviderCall() {
        UUID chatId = UUID.randomUUID(), userId = UUID.randomUUID(), orgId = UUID.randomUUID();
        SafeAiUserPrincipal principal = mock(SafeAiUserPrincipal.class);
        when(principal.getId()).thenReturn(userId);
        when(principal.getOrganizationId()).thenReturn(orgId);
        ChatSessionEntity chat = defaultChat(userId, orgId);
        when(sessions.findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(chatId, userId, orgId))
                .thenReturn(Optional.of(chat));
        when(sessions.saveAndFlush(chat)).thenReturn(chat);

        service.assignAutomaticTitle(chatId, new AutomaticChatTitleRequest("Проверка доступности модели. Ошибка."), principal);

        assertThat(chat.getTitleSource()).isEqualTo(ChatTitleSource.AUTO);
        assertThat(chat.getTitle()).isEqualTo("Проверка доступности модели.");
        verify(mutex).lockChat(chatId);
        verify(sessions).saveAndFlush(chat);
        verifyNoInteractions(audit);
    }

    @Test
    void manualRenameIsNeverOverwritten() {
        UUID chatId = UUID.randomUUID(), userId = UUID.randomUUID(), orgId = UUID.randomUUID();
        SafeAiUserPrincipal principal = mock(SafeAiUserPrincipal.class);
        when(principal.getId()).thenReturn(userId);
        when(principal.getOrganizationId()).thenReturn(orgId);
        ChatSessionEntity chat = defaultChat(userId, orgId);
        chat.renameByUser("Моё название", clock.instant());
        when(sessions.findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(chatId, userId, orgId))
                .thenReturn(Optional.of(chat));

        service.assignAutomaticTitle(chatId, new AutomaticChatTitleRequest("Нельзя изменить название"), principal);

        assertThat(chat.getTitle()).isEqualTo("Моё название");
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void foreignChatIsNotModified() {
        UUID chatId = UUID.randomUUID(), userId = UUID.randomUUID(), orgId = UUID.randomUUID();
        SafeAiUserPrincipal principal = mock(SafeAiUserPrincipal.class);
        when(principal.getId()).thenReturn(userId);
        when(principal.getOrganizationId()).thenReturn(orgId);
        when(sessions.findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull(chatId, userId, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignAutomaticTitle(
                chatId, new AutomaticChatTitleRequest("Чужой чат"), principal))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(sessions, never()).saveAndFlush(any());
    }

    private ChatSessionEntity defaultChat(UUID userId, UUID orgId) {
        OrganizationEntity org = new OrganizationEntity();
        org.setId(orgId);
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganization(org);
        return ChatSessionEntity.create(user, null, clock.instant());
    }
}
