package ru.safeai.gateway.usage.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageStatus;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
class UsageQueryRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID CHAT_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UsageQueryRepository repository;

    private OrganizationEntity organization;
    private ChatSessionEntity session;

    @BeforeEach
    void setUp() {
        organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Test Organization");
        organization.setEnabled(true);
        organization.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        organization.setUpdatedAt(Instant.parse("2026-06-01T00:00:00Z"));

        entityManager.persist(organization);

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setOrganization(organization);
        user.setEmail("user@test.com");
        user.setPasswordHash("encoded-password");
        user.setFullName("Test User");
        user.setEnabled(true);
        user.setTokenVersion(0L);

        entityManager.persist(user);

        session = new ChatSessionEntity();
        session.setId(CHAT_ID);
        session.setUser(user);
        session.setOrganization(organization);
        session.setTitle("Test Chat");
        session.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        session.setUpdatedAt(Instant.parse("2026-06-01T00:00:00Z"));

        entityManager.persist(session);
    }

    @Test
    void usageQueries_shouldExcludeFailedAssistantMessages() {
        persistMessage(
                ChatMessageRole.ASSISTANT,
                ChatMessageStatus.FAILED,
                "mock-safeai",
                10,
                20,
                BigDecimal.valueOf(0.01),
                Instant.parse("2026-06-12T12:00:00Z")
        );

        entityManager.flush();

        var result = repository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                null
        );

        assertThat(result).isEmpty();
    }

    @Test
    void usageQueries_shouldExcludeUserMessages() {
        persistMessage(
                ChatMessageRole.USER,
                ChatMessageStatus.COMPLETED,
                "mock-safeai",
                10,
                20,
                BigDecimal.valueOf(0.01),
                Instant.parse("2026-06-12T12:00:00Z")
        );

        entityManager.flush();

        var result = repository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                null
        );

        assertThat(result).isEmpty();
    }

    @Test
    void usageQueries_shouldExcludeMessagesWithNullModel() {
        persistMessage(
                ChatMessageRole.ASSISTANT,
                ChatMessageStatus.COMPLETED,
                null,
                10,
                20,
                BigDecimal.valueOf(0.01),
                Instant.parse("2026-06-12T12:00:00Z")
        );

        entityManager.flush();

        var result = repository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                null
        );

        assertThat(result).isEmpty();
    }

    @Test
    void findUsageDailyByOrganizationId_shouldGroupByUtcDate() {
        persistMessage(
                ChatMessageRole.ASSISTANT,
                ChatMessageStatus.COMPLETED,
                "mock-safeai",
                10,
                20,
                BigDecimal.valueOf(0.01),
                Instant.parse("2026-06-12T23:30:00Z")
        );

        persistMessage(
                ChatMessageRole.ASSISTANT,
                ChatMessageStatus.COMPLETED,
                "mock-safeai",
                30,
                40,
                BigDecimal.valueOf(0.02),
                Instant.parse("2026-06-13T00:30:00Z")
        );

        entityManager.flush();

        List<UsageDailySummaryProjection> result =
                repository.findUsageDailyByOrganizationId(
                        ORGANIZATION_ID,
                        Instant.parse("2026-06-12T00:00:00Z"),
                        Instant.parse("2026-06-14T00:00:00Z")
                );

        assertThat(result)
                .extracting(UsageDailySummaryProjection::getUsageDate)
                .containsExactly(
                        LocalDate.of(2026, 6, 13),
                        LocalDate.of(2026, 6, 12)
                );
    }

    private void persistMessage(
            ChatMessageRole role,
            ChatMessageStatus status,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            BigDecimal costUsd,
            Instant createdAt
    ) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setSession(session);
        message.setOrganization(organization);
        message.setRole(role);
        message.setStatus(status);
        message.setContent("Test message");
        message.setModel(model);
        message.setInputTokens(inputTokens);
        message.setOutputTokens(outputTokens);
        message.setCostUsd(costUsd);
        message.setCreatedAt(createdAt);

        entityManager.persist(message);
    }
}