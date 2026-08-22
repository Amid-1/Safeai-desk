package ru.safeai.gateway.usage.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.entity.ChatMessageRole;
import ru.safeai.gateway.chat.entity.ChatMessageStatus;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        properties = "safeai.usage.rollup.enabled=false"
)
@Testcontainers
@ActiveProfiles("test")
@Transactional
class UsageQueryRepositoryTest {

    private static final String UNATTRIBUTED_MODEL =
            "__unattributed__";

    private static final String DEFAULT_USER_CONTENT =
            "Test user request";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    "pgvector/pgvector:pg16"
            );

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
            Instant.parse(
                    "2026-06-01T00:00:00Z"
            );

    private static final Instant DATE_TO =
            Instant.parse(
                    "2026-07-01T00:00:00Z"
            );

    private static final Instant FIXTURE_CREATED_AT =
            Instant.parse(
                    "2026-06-01T00:00:00Z"
            );

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
    void aggregatesAvailableCompletedAssistantUsage() {
        persistCompletedAssistant(
                "mock-safeai",
                10,
                20,
                new BigDecimal("0.010000"),
                PricingStatus.PRICED,
                "priced-v1",
                Instant.parse(
                        "2026-06-12T12:00:00Z"
                )
        );

        persistCompletedAssistant(
                "mock-safeai",
                30,
                40,
                new BigDecimal("0.020000"),
                PricingStatus.PRICED,
                "priced-v1",
                Instant.parse(
                        "2026-06-13T12:00:00Z"
                )
        );

        flushAndClear();

        Slice<UsageSummaryResponse> result =
                findSummary(
                        null,
                        PAGEABLE
                );

        assertThat(result.getContent())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.userId())
                            .isEqualTo(USER_ID);

                    assertThat(item.model())
                            .isEqualTo("mock-safeai");

                    assertThat(
                            item.responses()
                                    .assistantMessages()
                    ).isEqualTo(2);

                    assertThat(
                            item.responses()
                                    .completedResponses()
                    ).isEqualTo(2);

                    assertThat(
                            item.usage()
                                    .confirmedInputTokens()
                    ).isEqualTo(40L);

                    assertThat(
                            item.usage()
                                    .confirmedOutputTokens()
                    ).isEqualTo(60L);

                    assertThat(
                            item.usage()
                                    .confirmedTotalTokens()
                    ).isEqualTo(100L);

                    assertThat(
                            item.usage()
                                    .usageComplete()
                    ).isTrue();

                    assertThat(
                            item.cost()
                                    .knownCostUsd()
                    ).isEqualByComparingTo(
                            "0.030000"
                    );

                    assertThat(
                            item.cost()
                                    .pricingComplete()
                    ).isTrue();
                });
    }

    @Test
    void missingAndPartialUsageRemainVisibleWithoutInflatingConfirmedTotals() {
        persistCompletedAssistantWithoutCompleteUsage(
                UsageStatus.MISSING,
                null,
                Instant.parse(
                        "2026-06-12T12:00:00Z"
                )
        );

        persistCompletedAssistantWithoutCompleteUsage(
                UsageStatus.PARTIAL,
                10,
                Instant.parse(
                        "2026-06-13T12:00:00Z"
                )
        );

        flushAndClear();

        Slice<UsageSummaryResponse> result =
                findSummary(
                        "gpt-4.1",
                        PAGEABLE
                );

        assertThat(result.getContent())
                .singleElement()
                .satisfies(item -> {
                    assertThat(
                            item.responses()
                                    .assistantMessages()
                    ).isEqualTo(2);

                    assertThat(
                            item.responses()
                                    .completedResponses()
                    ).isEqualTo(2);

                    assertThat(
                            item.usage()
                                    .confirmedTotalTokens()
                    ).isZero();

                    assertThat(
                            item.usage()
                                    .partialKnownInputTokens()
                    ).isEqualTo(10L);

                    assertThat(
                            item.usage()
                                    .partialKnownOutputTokens()
                    ).isZero();

                    assertThat(
                            item.usage()
                                    .partialKnownTotalTokens()
                    ).isEqualTo(10L);

                    assertThat(
                            item.usage()
                                    .availableUsageMessages()
                    ).isZero();

                    assertThat(
                            item.usage()
                                    .partialUsageMessages()
                    ).isEqualTo(1L);

                    assertThat(
                            item.usage()
                                    .missingUsageMessages()
                    ).isEqualTo(1L);

                    assertThat(
                            item.usage()
                                    .usageComplete()
                    ).isFalse();

                    assertThat(
                            item.cost()
                                    .knownCostUsd()
                    ).isZero();

                    assertThat(
                            item.cost()
                                    .unpricedMessages()
                    ).isEqualTo(2L);

                    assertThat(
                            item.cost()
                                    .pricingComplete()
                    ).isFalse();
                });
    }

    @Test
    void excludesUserMessagesButKeepsFailedAssistantAsNotApplicable() {
        Instant failedAt =
                Instant.parse(
                        "2026-06-12T12:00:00Z"
                );

        persistFailedAssistant(
                failedAt
        );

        persistUserMessage(
                "User message",
                Instant.parse(
                        "2026-06-13T12:00:00Z"
                )
        );

        flushAndClear();

        Slice<UsageSummaryResponse> result =
                findSummary(
                        null,
                        PAGEABLE
                );

        assertThat(result.getContent())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.model())
                            .isEqualTo(
                                    UNATTRIBUTED_MODEL
                            );

                    assertThat(
                            item.responses()
                                    .assistantMessages()
                    ).isEqualTo(1L);

                    assertThat(
                            item.responses()
                                    .failedMessages()
                    ).isEqualTo(1L);

                    assertThat(
                            item.responses()
                                    .completedResponses()
                    ).isZero();

                    assertThat(
                            item.usage()
                                    .confirmedTotalTokens()
                    ).isZero();

                    assertThat(
                            item.usage()
                                    .usageNotApplicableMessages()
                    ).isEqualTo(1L);

                    assertThat(
                            item.cost()
                                    .pricingNotApplicableMessages()
                    ).isEqualTo(1L);
                });
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
                Instant.parse(
                        "2026-06-12T12:00:00Z"
                )
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
                findSummary(
                        "mock-safeai",
                        PAGEABLE
                );

        assertThat(mockResult.getContent())
                .extracting(
                        UsageSummaryResponse::model
                )
                .containsExactly(
                        "mock-safeai"
                );

        Slice<UsageSummaryResponse> excludedResult =
                findSummary(
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
                Instant.parse(
                        "2026-06-12T12:00:00Z"
                )
        );

        persistCompletedAssistant(
                "model-b",
                30,
                40,
                new BigDecimal("0.020000"),
                PricingStatus.PRICED,
                "v1",
                Instant.parse(
                        "2026-06-13T12:00:00Z"
                )
        );

        flushAndClear();

        Pageable oneItemPage =
                PageRequest.of(0, 1);

        Slice<UsageSummaryResponse> result =
                findSummary(
                        null,
                        oneItemPage
                );

        assertThat(result.getContent())
                .hasSize(1);

        assertThat(result.hasNext())
                .isTrue();

        assertThat(result.getNumber())
                .isZero();

        assertThat(result.getSize())
                .isEqualTo(1);
    }

    @Test
    void dailyReportGroupsByUtcDate() {
        Instant dailyFrom =
                Instant.parse(
                        "2026-06-12T00:00:00Z"
                );

        Instant dailyTo =
                Instant.parse(
                        "2026-06-14T00:00:00Z"
                );

        persistCompletedAssistant(
                "mock-safeai",
                10,
                20,
                new BigDecimal("0.010000"),
                PricingStatus.PRICED,
                "v1",
                Instant.parse(
                        "2026-06-12T23:30:00Z"
                )
        );

        persistCompletedAssistant(
                "mock-safeai",
                30,
                40,
                new BigDecimal("0.020000"),
                PricingStatus.PRICED,
                "v1",
                Instant.parse(
                        "2026-06-13T00:30:00Z"
                )
        );

        flushAndClear();

        List<UsageDailySummaryResponse> result =
                repository.findDaily(
                        criteria(
                                null,
                                dailyFrom,
                                dailyTo
                        ),
                        livePlan(
                                dailyFrom,
                                dailyTo
                        )
                );

        assertThat(result)
                .extracting(
                        UsageDailySummaryResponse::usageDate
                )
                .containsExactly(
                        LocalDate.of(2026, 6, 13),
                        LocalDate.of(2026, 6, 12)
                );

        assertThat(result)
                .allSatisfy(item ->
                        assertThat(
                                item.aggregationZone()
                        ).isEqualTo("UTC")
                );
    }

    private Slice<UsageSummaryResponse> findSummary(
            String model,
            Pageable pageable
    ) {
        return repository.findSummary(
                criteria(
                        model,
                        DATE_FROM,
                        DATE_TO
                ),
                livePlan(
                        DATE_FROM,
                        DATE_TO
                ),
                pageable
        );
    }

    private UsageQueryCriteria criteria(
            String model,
            Instant dateFrom,
            Instant dateTo
    ) {
        return new UsageQueryCriteria(
                dateFrom,
                dateTo,
                ORGANIZATION_ID,
                null,
                model
        );
    }

    private UsageQueryPlan livePlan(
            Instant dateFrom,
            Instant dateTo
    ) {
        return new UsageQueryPlan(
                null,
                null,
                List.of(
                        new UsageInstantRange(
                                dateFrom,
                                dateTo
                        )
                )
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
        UUID userMessageId =
                persistUserMessage(
                        DEFAULT_USER_CONTENT,
                        userMessageCreatedAt(
                                createdAt
                        )
                );

        ChatMessageEntity message =
                baseAssistantMessage(
                        userMessageId,
                        ChatMessageStatus.COMPLETED,
                        "Assistant response",
                        createdAt
                );

        message.setRequestedModel(model);
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
        UUID userMessageId =
                persistUserMessage(
                        DEFAULT_USER_CONTENT,
                        userMessageCreatedAt(
                                createdAt
                        )
                );

        ChatMessageEntity message =
                baseAssistantMessage(
                        userMessageId,
                        ChatMessageStatus.COMPLETED,
                        "Assistant response",
                        createdAt
                );

        message.setRequestedModel("gpt-4.1");
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

        message.setCostUsd(null);

        message.setPricingStatus(
                PricingStatus.UNPRICED
        );

        message.setCurrency(null);
        message.setPricingVersion(null);

        message.setPricingCalculatedAt(
                createdAt
        );

        entityManager.persist(message);
    }

    private void persistFailedAssistant(
            Instant createdAt
    ) {
        UUID userMessageId =
                persistUserMessage(
                        DEFAULT_USER_CONTENT,
                        userMessageCreatedAt(
                                createdAt
                        )
                );

        ChatMessageEntity message =
                baseAssistantMessage(
                        userMessageId,
                        ChatMessageStatus.FAILED,
                        "Failed",
                        createdAt
                );

        message.setUsageStatus(
                UsageStatus.NOT_APPLICABLE
        );

        message.setPricingStatus(
                PricingStatus.NOT_APPLICABLE
        );

        entityManager.persist(message);
    }

    private UUID persistUserMessage(
            String content,
            Instant createdAt
    ) {
        ChatMessageEntity message =
                new ChatMessageEntity();

        UUID messageId =
                UUID.randomUUID();

        message.setId(messageId);
        message.setSession(session);
        message.setOrganization(organization);
        message.setRole(ChatMessageRole.USER);
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setContent(content);

        message.setClientRequestId(
                UUID.randomUUID()
        );

        message.setCreatedAt(createdAt);

        message.setUsageStatus(
                UsageStatus.NOT_APPLICABLE
        );

        message.setPricingStatus(
                PricingStatus.NOT_APPLICABLE
        );

        entityManager.persist(message);

        return messageId;
    }

    private ChatMessageEntity baseAssistantMessage(
            UUID replyToMessageId,
            ChatMessageStatus status,
            String content,
            Instant createdAt
    ) {
        ChatMessageEntity message =
                new ChatMessageEntity();

        message.setId(UUID.randomUUID());
        message.setSession(session);
        message.setOrganization(organization);
        message.setRole(ChatMessageRole.ASSISTANT);
        message.setStatus(status);
        message.setContent(content);

        message.setReplyToMessageId(
                replyToMessageId
        );

        message.setCreatedAt(createdAt);

        return message;
    }

    private Instant userMessageCreatedAt(
            Instant assistantCreatedAt
    ) {
        return assistantCreatedAt.minusSeconds(1);
    }

    private OrganizationEntity createOrganization() {
        OrganizationEntity entity =
                new OrganizationEntity();

        entity.setId(ORGANIZATION_ID);
        entity.setName("Test Organization");
        entity.setEnabled(true);

        entity.setCreatedAt(
                FIXTURE_CREATED_AT
        );

        entity.setUpdatedAt(
                FIXTURE_CREATED_AT
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
                FIXTURE_CREATED_AT
        );

        entity.setUpdatedAt(
                FIXTURE_CREATED_AT
        );

        return entity;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
