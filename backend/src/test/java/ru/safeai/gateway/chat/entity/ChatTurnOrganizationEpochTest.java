package ru.safeai.gateway.chat.entity;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTurnOrganizationEpochTest {
    @Test
    void factoryCapturesOrganizationEpochBeforePersistence() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setAuthVersion(7L);

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setOrganization(organization);

        ChatSessionEntity session = ChatSessionEntity.create(
                user, "Test chat", Instant.parse("2026-10-10T00:00:00Z"));
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        ChatTurnEntity turn = ChatTurnEntity.processing(
                UUID.randomUUID(), session, UUID.randomUUID(), "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                now, now.plusSeconds(120), "mock", "mock-model",
                UUID.randomUUID(), null, KnowledgeMode.GENERAL);
        assertThat(turn.getAuthorizedOrganizationAuthVersion()).isEqualTo(7L);
    }
}
