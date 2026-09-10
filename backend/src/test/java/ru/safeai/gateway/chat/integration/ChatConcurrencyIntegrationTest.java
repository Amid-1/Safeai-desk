package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.exception.ChatQuotaExceededException;
import ru.safeai.gateway.chat.exception.ChatTurnInProgressException;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.chat.service.ChatTurnReservationService;
import ru.safeai.gateway.common.exception.ChatBusyException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=true",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class ChatConcurrencyIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    private static final long READY_TIMEOUT_SECONDS = 10;
    private static final long RESULT_TIMEOUT_SECONDS = 20;

    /*
     * This integration test is intentionally pinned to the application's
     * test Runtime configured by AbstractPostgresIntegrationTest.
     *
     * Keeping these values explicit makes a future test-runtime change fail
     * visibly instead of silently turning a concurrency test into a routing
     * test.
     */
    private static final String TEST_RUNTIME_PROVIDER = "mock";
    private static final String TEST_RUNTIME_MODEL = "mock-safeai";
    private static final String TEST_MODEL_KEY = "mock:mock-safeai";

    private static final UUID TEST_CATALOG_ENTRY_ID =
            UUID.fromString(
                    "91919191-9191-4919-8919-919191919191"
            );

    @Autowired
    private ChatTurnReservationService reservationService;

    @Autowired
    private ModelCatalogRepository modelCatalogRepository;

    @Autowired
    private RuntimeModelStatusService runtimeModelStatusService;

    @Autowired
    private Clock clock;

    @BeforeEach
    void prepareGovernedRuntimeAndPrimaryChat() {
        /*
         * Strict Model Control Plane routing no longer permits a physical
         * Runtime without an effective catalog snapshot.
         *
         * Model catalog rows are append-only and are not safely reusable as
         * per-test mutable fixtures. TRUNCATE is appropriate only here, in an
         * isolated Testcontainers database, and avoids accumulating multiple
         * effective logical keys for the same Runtime (which would correctly
         * become AMBIGUOUS_RUNTIME_MAPPING).
         */
        resetModelControlPlaneFixtures();

        RuntimeModelStatusResponse runtime =
                runtimeModelStatusService.current();

        assertThat(runtime.provider())
                .as("Test Runtime provider")
                .isEqualTo(TEST_RUNTIME_PROVIDER);

        assertThat(runtime.model())
                .as("Test Runtime model")
                .isEqualTo(TEST_RUNTIME_MODEL);

        assertThat(runtime.enabled())
                .as("Test Runtime must be executable")
                .isTrue();

        modelCatalogRepository.insert(
                effectiveCatalogEntry(runtime)
        );

        assertThat(
                modelCatalogRepository.findEffectiveByRuntime(
                        runtime.provider(),
                        runtime.model(),
                        clock.instant()
                )
        )
                .as(
                        "Concurrency tests require exactly one effective "
                                + "logical catalog mapping for the physical Runtime"
                )
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.modelKey())
                            .isEqualTo(TEST_MODEL_KEY);

                    assertThat(entry.lifecycle())
                            .isEqualTo(ModelLifecycle.ACTIVE);
                });

        alignChatTimestamps(CHAT_ID);
        assertOwnedChatVisible(CHAT_ID);
    }

    @Test
    void concurrentDuplicateRequestsCreateOneUserMessageAndOneTurn()
            throws Exception {

        UUID clientRequestId = UUID.randomUUID();

        List<Object> outcomes = race(
                () -> reserve(
                        CHAT_ID,
                        clientRequestId,
                        "Question"
                ),
                () -> reserve(
                        CHAT_ID,
                        clientRequestId,
                        "Question"
                )
        );

        /*
         * Same clientRequestId means the same logical request is already
         * being processed. Exactly one reservation succeeds.
         */
        assertRaceOutcomes(
                outcomes,
                ChatTurnInProgressException.class
        );

        assertThat(countRows(Table.CHAT_TURNS))
                .isEqualTo(1);

        assertThat(countRows(Table.CHAT_MESSAGES))
                .isEqualTo(1);

        assertThat(countRows(Table.CHAT_QUOTA_RESERVATIONS))
                .isEqualTo(1);
    }

    @Test
    void differentClientRequestIdsInOneChatCannotProcessInParallel()
            throws Exception {

        UUID firstClientRequestId = UUID.randomUUID();
        UUID secondClientRequestId = UUID.randomUUID();

        List<Object> outcomes = race(
                () -> reserve(
                        CHAT_ID,
                        firstClientRequestId,
                        "First"
                ),
                () -> reserve(
                        CHAT_ID,
                        secondClientRequestId,
                        "Second"
                )
        );

        /*
         * Different clientRequestId values are different requests. A chat has
         * one processing turn at a time, so the loser must fail as CHAT_BUSY.
         */
        assertRaceOutcomes(
                outcomes,
                ChatBusyException.class
        );

        assertThat(countRows(Table.CHAT_TURNS))
                .isEqualTo(1);

        assertThat(countRows(Table.CHAT_MESSAGES))
                .isEqualTo(1);

        assertThat(countRows(Table.CHAT_QUOTA_RESERVATIONS))
                .isEqualTo(1);
    }

    @Test
    void organizationQuotaReservationIsAtomicAcrossTwoChats()
            throws Exception {

        UUID secondChatId = UUID.randomUUID();

        insertChatSession(
                secondChatId,
                USER_ID,
                ORGANIZATION_ID
        );

        alignChatTimestamps(secondChatId);

        Timestamp quotaTimestamp =
                Timestamp.from(fixtureTimestamp());

        jdbcTemplate.update(
                """
                insert into organization_ai_quotas (
                    organization_id,
                    enabled,
                    monthly_request_limit,
                    created_at,
                    updated_at,
                    version
                ) values (
                    ?,
                    true,
                    1,
                    ?,
                    ?,
                    0
                )
                """,
                ORGANIZATION_ID,
                quotaTimestamp,
                quotaTimestamp
        );

        assertOwnedChatVisible(CHAT_ID);
        assertOwnedChatVisible(secondChatId);

        UUID firstClientRequestId = UUID.randomUUID();
        UUID secondClientRequestId = UUID.randomUUID();

        List<Object> outcomes = race(
                () -> reserve(
                        CHAT_ID,
                        firstClientRequestId,
                        "First"
                ),
                () -> reserve(
                        secondChatId,
                        secondClientRequestId,
                        "Second"
                )
        );

        assertRaceOutcomes(
                outcomes,
                ChatQuotaExceededException.class
        );

        assertThat(countRows(Table.CHAT_TURNS))
                .isEqualTo(1);

        assertThat(countRows(Table.CHAT_MESSAGES))
                .isEqualTo(1);

        assertThat(countRows(Table.CHAT_QUOTA_RESERVATIONS))
                .isEqualTo(1);
    }

    private void resetModelControlPlaneFixtures() {
        jdbcTemplate.execute(
                """
                truncate table
                    public.model_route_decisions,
                    public.organization_model_policies,
                    public.model_catalog_entries
                cascade
                """
        );
    }

    private ModelCatalogEntry effectiveCatalogEntry(
            RuntimeModelStatusResponse runtime
    ) {
        Instant effectiveFrom =
                clock.instant().minusSeconds(1);

        Instant createdAt =
                effectiveFrom.minusSeconds(1);

        /*
         * Chat concurrency/quota tests must not depend on external provider
         * pricing. A FREE catalog snapshot isolates the test to reservation
         * semantics while still exercising the real strict routing path.
         */
        return new ModelCatalogEntry(
                TEST_CATALOG_ENTRY_ID,
                TEST_MODEL_KEY,
                1,
                runtime.provider(),
                runtime.model(),
                "SafeAI integration-test Runtime",
                ModelLifecycle.ACTIVE,
                runtime.maxInputTokens(),
                runtime.maxOutputTokens(),
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                "{}",
                "integration-test-free-v1",
                effectiveFrom,
                ModelCatalogSource.RUNTIME_IMPORT,
                USER_ID,
                createdAt
        );
    }

    private ChatProcessingContext reserve(
            UUID chatId,
            UUID clientRequestId,
            String content
    ) {
        return reservationService.reserveOrReplay(
                chatId,
                new SendMessageRequest(
                        content,
                        clientRequestId
                ),
                testPrincipal()
        );
    }

    private SafeAiUserPrincipal testPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER"
                        )
                )
        );
    }

    private void alignChatTimestamps(
            UUID chatId
    ) {
        Timestamp timestamp =
                Timestamp.from(fixtureTimestamp());

        int updated = jdbcTemplate.update(
                """
                update public.chat_sessions
                   set created_at = ?,
                       updated_at = ?
                 where id = ?
                """,
                timestamp,
                timestamp,
                chatId
        );

        assertThat(updated)
                .as(
                        "Должен существовать тестовый чат для "
                                + "синхронизации timestamp: chatId=%s",
                        chatId
                )
                .isEqualTo(1);
    }

    private Instant fixtureTimestamp() {
        /*
         * The integration Clock is fixed. The chat is placed one second
         * before the timestamp used by the reservation service.
         */
        return clock.instant().minusSeconds(1);
    }

    private void assertOwnedChatVisible(
            UUID chatId
    ) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                select exists (
                    select 1
                    from public.chat_sessions
                    where id = ?
                      and user_id = ?
                      and organization_id = ?
                )
                """,
                Boolean.class,
                chatId,
                USER_ID,
                ORGANIZATION_ID
        );

        assertThat(exists)
                .as(
                        "Перед запуском race чат должен существовать: "
                                + "chatId=%s, userId=%s, organizationId=%s",
                        chatId,
                        USER_ID,
                        ORGANIZATION_ID
                )
                .isTrue();
    }

    private List<Object> race(
            Callable<ChatProcessingContext> first,
            Callable<ChatProcessingContext> second
    ) throws Exception {

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<Object> firstFuture =
                    executor.submit(
                            () -> run(
                                    ready,
                                    start,
                                    first
                            )
                    );

            Future<Object> secondFuture =
                    executor.submit(
                            () -> run(
                                    ready,
                                    start,
                                    second
                            )
                    );

            try {
                boolean bothReady = ready.await(
                        READY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );

                assertThat(bothReady)
                        .as(
                                "Оба конкурентных задания должны "
                                        + "дойти до точки старта"
                        )
                        .isTrue();

                start.countDown();

                Object firstOutcome =
                        firstFuture.get(
                                RESULT_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        );

                Object secondOutcome =
                        secondFuture.get(
                                RESULT_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        );

                return List.of(
                        firstOutcome,
                        secondOutcome
                );
            } catch (Exception | AssertionError failure) {
                cancelRace(
                        start,
                        executor
                );

                throw failure;
            }
        }
    }

    private static void cancelRace(
            CountDownLatch start,
            ExecutorService executor
    ) {
        /*
         * Releases workers if setup failed before the regular start signal.
         */
        start.countDown();

        /*
         * Interrupts workers that may still be blocked. try-with-resources
         * closes the executor after this method returns.
         */
        executor.shutdownNow();
    }

    private Object run(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<ChatProcessingContext> action
    ) {
        ready.countDown();

        try {
            boolean started = start.await(
                    READY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!started) {
                return new IllegalStateException(
                        "Истекло время ожидания старта race"
                );
            }

            return action.call();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return exception;
        } catch (Exception exception) {
            /*
             * An expected concurrency conflict is returned to the coordinator
             * thread as one race outcome.
             */
            return exception;
        }
    }

    private static void assertRaceOutcomes(
            List<Object> outcomes,
            Class<? extends Throwable> expectedFailureType
    ) {
        String description =
                describeOutcomes(outcomes);

        assertThat(outcomes)
                .as(
                        "Результаты конкурентного запуска: %s",
                        description
                )
                .hasSize(2);

        long successCount = outcomes.stream()
                .filter(
                        ChatProcessingContext.class::isInstance
                )
                .count();

        long expectedFailureCount = outcomes.stream()
                .filter(
                        expectedFailureType::isInstance
                )
                .count();

        assertThat(successCount)
                .as(
                        "Ожидался один ChatProcessingContext. "
                                + "Получено: %s",
                        description
                )
                .isEqualTo(1);

        assertThat(expectedFailureCount)
                .as(
                        "Ожидалось одно исключение %s. Получено: %s",
                        expectedFailureType.getSimpleName(),
                        description
                )
                .isEqualTo(1);

        assertThat(outcomes)
                .as(
                        "Не должно быть неожиданных результатов: %s",
                        description
                )
                .allMatch(outcome ->
                        outcome instanceof ChatProcessingContext
                                || expectedFailureType.isInstance(
                                        outcome
                                )
                );
    }

    private static String describeOutcomes(
            List<Object> outcomes
    ) {
        return outcomes.stream()
                .map(
                        ChatConcurrencyIntegrationTest
                                ::describeOutcome
                )
                .toList()
                .toString();
    }

    private static String describeOutcome(
            Object outcome
    ) {
        if (outcome == null) {
            return "null";
        }

        if (outcome instanceof Throwable throwable) {
            String message =
                    throwable.getMessage();

            return throwable.getClass().getName()
                    + (
                    message == null
                            ? ""
                            : ": " + message
            );
        }

        return outcome.getClass().getName();
    }

    private long countRows(
            Table table
    ) {
        String sql = switch (table) {
            case CHAT_TURNS ->
                    "select count(*) from public.chat_turns";

            case CHAT_MESSAGES ->
                    "select count(*) from public.chat_messages";

            case CHAT_QUOTA_RESERVATIONS ->
                    """
                    select count(*)
                    from public.chat_quota_reservations
                    """;
        };

        Long result = jdbcTemplate.queryForObject(
                sql,
                Long.class
        );

        return result == null
                ? 0L
                : result;
    }

    private enum Table {
        CHAT_TURNS,
        CHAT_MESSAGES,
        CHAT_QUOTA_RESERVATIONS
    }
}
