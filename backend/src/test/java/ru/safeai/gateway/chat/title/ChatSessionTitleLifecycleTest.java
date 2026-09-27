// backend/src/test/java/ru/safeai/gateway/chat/title/ChatSessionTitleLifecycleTest.java
package ru.safeai.gateway.chat.title;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionTitleLifecycleTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-09-27T12:00:00Z");

    @Test
    void automaticTitleCanReplaceDefaultOnlyOnce() {
        ChatSessionEntity session = ChatSessionEntity.create(
                user(),
                null,
                CREATED_AT
        );

        assertThat(session.getTitle()).isEqualTo("Новый чат");
        assertThat(session.getTitleSource()).isEqualTo(ChatTitleSource.DEFAULT);

        assertThat(session.assignAutomaticTitle(
                "Архитектура авторизации",
                CREATED_AT.plusSeconds(1)
        )).isTrue();

        assertThat(session.getTitle()).isEqualTo("Архитектура авторизации");
        assertThat(session.getTitleSource()).isEqualTo(ChatTitleSource.AUTO);

        assertThat(session.assignAutomaticTitle(
                "Это название уже не должно примениться",
                CREATED_AT.plusSeconds(2)
        )).isFalse();
        assertThat(session.getTitle()).isEqualTo("Архитектура авторизации");
    }

    @Test
    void userRenamePermanentlyWinsOverAutomaticNaming() {
        ChatSessionEntity session = ChatSessionEntity.create(
                user(),
                null,
                CREATED_AT
        );

        session.renameByUser(
                "Проект SafeAI",
                CREATED_AT.plusSeconds(1)
        );

        assertThat(session.getTitleSource()).isEqualTo(ChatTitleSource.USER);
        assertThat(session.assignAutomaticTitle(
                "Новый автоматический заголовок",
                CREATED_AT.plusSeconds(2)
        )).isFalse();
        assertThat(session.getTitle()).isEqualTo("Проект SafeAI");
    }

    private static UserEntity user() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Test Organization");
        organization.setEnabled(true);

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setOrganization(organization);
        user.setEmail("user@test.local");
        user.setPasswordHash("hash");
        user.setEnabled(true);
        return user;
    }
}
