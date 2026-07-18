package ru.safeai.gateway.usage.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageStatus;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;

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
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID CHAT_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final Instant DATE_FROM =
            Instant.parse("2026-06-01T00:00:00Z");

    private static final Instant DATE_TO =
            Instant.parse("2026-07-01T00:00:00Z");

    private static final Pageable PAGEABLE =
            PageRequest.of(0, 50);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UsageQueryRepository repository;

    private OrganizationEntity organization;
    private ChatSessionEntity session;

    @BeforeEach
    void setUp() {
        organization = createOrganization();

        UserEntity user =
                createUser(organization);

        session = createSession(
                organization,
                user
        );

        entityManager.persist(organization);
        entityManager.persist(user);
        entityManager.persist(session);
    }

    @Test
    void aggregatesOnlyAvailableCompletedAssistantUsage() {
        persistCompletedAssistant(
                "mock-safeai",
                10,
                20,
                new BigDecimal("0.010000"),
                PricingStatus.PRICED,
                "priced-v1",
                Instant.parse("2026-06-12T12:00:00Z")
        );

        persistCompletedAssistant(
                "mock-safeai",
                30,
                40,
                new BigDecimal("0.020000"),
                PricingStatus.PRICED,
                "priced-v1",
                Instant.parse("2026-06-13T12:00:00Z")
        );

        flushAndClear();

        Slice<UsageSummaryResponse> result =
                repository.findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        null,
                        PAGEABLE
                );

        assertThat(result.getContent())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.userId())
                            .isEqualTo(USER_ID);

                    assertThat(item.inputTokens())
                            .isEqualTo(40L);

                    assertThat(item.outputTokens())
                            .isEqualTo(60L);

                    assertThat(item.totalTokens())
                            .isEqualTo(100L);

                    assertThat(item.costUsd())
                            .isEqualByComparingTo(
                                    "0.030000"
                            );
                });
    }

    @Test
    void excludesMissingAndPartialUsage() {
        persistCompletedAssistantWithoutCompleteUsage(
                UsageStatus.MISSING,
                null,
                Instant.parse("2026-06-12T12:00:00Z")
        );

        persistCompletedAssistantWithoutCompleteUsage(
                UsageStatus.PARTIAL,
                10,
                Instant.parse("2026-06-13T12:00:00Z")
        );

        flushAndClear();

        Slice<UsageSummaryResponse> result =
                repository.findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        null,
                        PAGEABLE
                );

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void excludesFailedAssistantAndUserMessages() {
        ChatMessageEntity failed = baseMessage(
                ChatMessageRole.ASSISTANT,
                ChatMessageStatus.FAILED,
                "Failed",
                Instant.parse("2026-06-12T12:00:00Z")
        );

        failed.setUsageStatus(
                UsageStatus.NOT_APPLICABLE
        );

        failed.setPricingStatus(
                PricingStatus.NOT_APPLICABLE
        );

        entityManager.persist(failed);

        ChatMessageEntity userMessage = baseMessage(
                ChatMessageRole.USER,
                ChatMessageStatus.COMPLETED,
                "User message",
                Instant.parse("2026-06-13T12:00:00Z")
        );

        userMessage.setUsageStatus(
                UsageStatus.NOT_APPLICABLE
        );

        userMessage.setPricingStatus(
                PricingStatus.NOT_APPLICABLE
        );

        entityManager.persist(userMessage);

        flushAndClear();

        Slice<UsageSummaryResponse> result =
                repository.findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        null,
                        PAGEABLE
                );

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void appliesModelFilterAndExclusiveDateTo() {
        persistCompletedAssistant(
                "mock-safeai",
                10,
                20,
                BigDecimal.ZERO,
                PricingStatus.FREE,
                "mock-v1",
                Instant.parse("2026-06-12T12:00:00Z")
        );

        persistCompletedAssistant(
                "gpt-4.1",
                30,
                40,
                new BigDecimal("0.020000"),
                PricingStatus.PRICED,
                "openai-v1",
                DATE_TO
        );

        flushAndClear();

        Slice<UsageSummaryResponse> mockResult =
                repository.findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        "mock-safeai",
                        PAGEABLE
                );

        assertThat(mockResult.getContent())
                .extracting(
                        UsageSummaryResponse::model
                )
                .containsExactly("mock-safeai");

        Slice<UsageSummaryResponse> excludedResult =
                repository.findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        "gpt-4.1",
                        PAGEABLE
                );

        assertThat(excludedResult.getContent())
                .isEmpty();
    }

    @Test
    void paginationReturnsRequestedSlice() {
        persistCompletedAssistant(
                "model-a",
                10,
                20,
                new BigDecimal("0.010000"),
                PricingStatus.PRICED,
                "v1",
                Instant.parse("2026-06-12T12:00:00Z")
        );

        persistCompletedAssistant(
                "model-b",
                30,
                40,
                new BigDecimal("0.020000"),
                PricingStatus.PRICED,
                "v1",
                Instant.parse("2026-06-13T12:00:00Z")
        );

        flushAndClear();

        Pageable oneItemPage =
                PageRequest.of(0, 1);

        Slice<UsageSummaryResponse> result =
                repository.findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        null,
                        oneItemPage
                );

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(1);
    }

    @Test
    void dailyReportGroupsByUtcDate() {
        persistCompletedAssistant(
                "mock-safeai",
                10,
                20,
                new BigDecimal("0.010000"),
                PricingStatus.PRICED,
                "v1",
                Instant.parse("2026-06-12T23:30:00Z")
        );

        persistCompletedAssistant(
                "mock-safeai",
                30,
                40,
                new BigDecimal("0.020000"),
                PricingStatus.PRICED,
                "v1",
                Instant.parse("2026-06-13T00:30:00Z")
        );

        flushAndClear();

        List<UsageDailySummaryProjection> result =
                repository
                        .findUsageDailyByOrganizationId(
                                ORGANIZATION_ID,
                                Instant.parse(
                                        "2026-06-12T00:00:00Z"
                                ),
                                Instant.parse(
                                        "2026-06-14T00:00:00Z"
                                )
                        );

        assertThat(result)
                .extracting(
                        UsageDailySummaryProjection::getUsageDate
                )
                .containsExactly(
                        LocalDate.of(2026, 6, 13),
                        LocalDate.of(2026, 6, 12)
                );
    }

    private void persistCompletedAssistant(
            String model,
            int inputTokens,
            int outputTokens,
            BigDecimal costUsd,
            PricingStatus pricingStatus,
            String pricingVersion,
            Instant createdAt
    ) {
        ChatMessageEntity message = baseMessage(
                ChatMessageRole.ASSISTANT,
                ChatMessageStatus.COMPLETED,
                "Assistant response",
                createdAt
        );

        message.setModel(model);

        message.setProviderMessageId(
                UUID.randomUUID().toString()
        );

        message.setAiResponseStatus(
                AiResponseStatus.COMPLETED
        );

        message.setFinishReason("completed");
        message.setInputTokens(inputTokens);
        message.setOutputTokens(outputTokens);

        message.setUsageStatus(
                UsageStatus.AVAILABLE
        );

        message.setCostUsd(costUsd);

        message.setPricingStatus(
                pricingStatus
        );

        message.setCurrency("USD");

        message.setPricingVersion(
                pricingVersion
        );

        message.setPricingCalculatedAt(
                createdAt
        );

        entityManager.persist(message);
    }

    private void persistCompletedAssistantWithoutCompleteUsage(
            UsageStatus usageStatus,
            Integer inputTokens,
            Instant createdAt
    ) {
        ChatMessageEntity message = baseMessage(
                ChatMessageRole.ASSISTANT,
                ChatMessageStatus.COMPLETED,
                "Assistant response",
                createdAt
        );

        message.setModel("gpt-4.1");

        message.setProviderMessageId(
                UUID.randomUUID().toString()
        );

        message.setAiResponseStatus(
                AiResponseStatus.COMPLETED
        );

        message.setFinishReason("completed");
        message.setInputTokens(inputTokens);
        message.setOutputTokens(null);
        message.setUsageStatus(usageStatus);

        message.setPricingStatus(
                PricingStatus.UNPRICED
        );

        message.setPricingCalculatedAt(
                createdAt
        );

        entityManager.persist(message);
    }

    private ChatMessageEntity baseMessage(
            ChatMessageRole role,
            ChatMessageStatus status,
            String content,
            Instant createdAt
    ) {
        ChatMessageEntity message =
                new ChatMessageEntity();

        message.setId(UUID.randomUUID());
        message.setSession(session);
        message.setOrganization(organization);
        message.setRole(role);
        message.setStatus(status);
        message.setContent(content);
        message.setCreatedAt(createdAt);

        return message;
    }

    private OrganizationEntity createOrganization() {
        OrganizationEntity entity =
                new OrganizationEntity();

        entity.setId(ORGANIZATION_ID);
        entity.setName("Test Organization");
        entity.setEnabled(true);

        entity.setCreatedAt(
                Instant.parse("2026-06-01T00:00:00Z")
        );

        entity.setUpdatedAt(
                Instant.parse("2026-06-01T00:00:00Z")
        );

        return entity;
    }

    private UserEntity createUser(
            OrganizationEntity targetOrganization
    ) {
        UserEntity entity =
                new UserEntity();

        entity.setId(USER_ID);
        entity.setOrganization(
                targetOrganization
        );
        entity.setEmail("user@test.com");
        entity.setPasswordHash(
                "encoded-password"
        );
        entity.setFullName("Test User");
        entity.setEnabled(true);
        entity.setTokenVersion(0L);

        return entity;
    }

    private ChatSessionEntity createSession(
            OrganizationEntity targetOrganization,
            UserEntity targetUser
    ) {
        ChatSessionEntity entity =
                new ChatSessionEntity();

        entity.setId(CHAT_ID);
        entity.setUser(targetUser);

        entity.setOrganization(
                targetOrganization
        );

        entity.setTitle("Test Chat");

        entity.setCreatedAt(
                Instant.parse("2026-06-01T00:00:00Z")
        );

        entity.setUpdatedAt(
                Instant.parse("2026-06-01T00:00:00Z")
        );

        return entity;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}