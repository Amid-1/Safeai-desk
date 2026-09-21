package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.AfterEach;
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
import java.util.stream.Collectors;

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

    private static final String MOCK_MODEL_KEY = "mock-safeai";

    private static final long READY_TIMEOUT_SECONDS = 10;
    private static final long RESULT_TIMEOUT_SECONDS = 20;

    @Autowired
    private ChatTurnReservationService reservationService;

    @Autowired
    private Clock clock;

    @BeforeEach
    void alignPrimaryChatWithTestClock() {
        // Superclass DB cleanup does not truncate the append-only model catalog.
        // Reset test-owned catalog rows so all three races start independently.
        jdbcTemplate.execute("truncate table public.model_catalog_entries cascade");
        alignChatTimestamps(CHAT_ID);
        seedEffectiveMockModelCatalog();
    }

    @AfterEach
    void removeRaceCatalogFixture() {
        jdbcTemplate.execute("truncate table public.model_catalog_entries cascade");
    }

    /**
     * V52+ requires every new ALLOWED route to bind an effective immutable
     * catalog snapshot. Each test starts with a freshly truncated database,
     * so the mock runtime needs an explicit catalog fixture before racing.
     */
    private void seedEffectiveMockModelCatalog() {
        Instant effectiveFrom = clock.instant().minusSeconds(1);

        int inserted = jdbcTemplate.update(
                """
                insert into public.model_catalog_entries (
                    id,
                    model_key,
                    version,
                    provider,
                    provider_model_id,
                    display_name,
                    lifecycle,
                    max_input_tokens,
                    max_output_tokens,
                    retention_status,
                    training_use_status,
                    pricing_status,
                    pricing_complete,
                    input_usd_per_1m_tokens,
                    output_usd_per_1m_tokens,
                    pricing_version,
                    effective_from,
                    source,
                    created_by_user_id,
                    created_at
                ) values (
                    ?,
                    ?,
                    1,
                    'mock',
                    'mock-safeai',
                    'Mock SafeAI',
                    'ACTIVE',
                    64000,
                    2048,
                    'NOT_DECLARED',
                    'NOT_DECLARED',
                    'FREE',
                    true,
                    0,
                    0,
                    'mock-2026-01',
                    ?,
                    'RUNTIME_IMPORT',
                    ?,
                    ?
                )
                """,
                UUID.randomUUID(),
                MOCK_MODEL_KEY,
                Timestamp.from(effectiveFrom),
                USER_ID,
                Timestamp.from(effectiveFrom)
        );

        assertThat(inserted)
                .as("Тестовый mock runtime должен иметь эффективную модель в каталоге")
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateRequestsCreateOneUserMessageAndOneTurn()
            throws Exception {

        assertOwnedChatVisible(CHAT_ID);

        UUID clientRequestId = UUID.randomUUID();

        List<RaceOutcome> outcomes = race(
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
         * Одинаковый clientRequestId означает повтор того же запроса,
         * который в этот момент уже обрабатывается.
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

        assertSingleAllowedRouteBoundToMockCatalog();
    }

    @Test
    void differentClientRequestIdsInOneChatCannotProcessInParallel()
            throws Exception {

        assertOwnedChatVisible(CHAT_ID);

        UUID firstClientRequestId = UUID.randomUUID();
        UUID secondClientRequestId = UUID.randomUUID();

        List<RaceOutcome> outcomes = race(
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
         * Разные clientRequestId означают разные запросы.
         * Пока первый запрос обрабатывается, второй получает ChatBusyException.
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

        assertSingleAllowedRouteBoundToMockCatalog();
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

        List<RaceOutcome> outcomes = race(
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

        assertSingleAllowedRouteBoundToMockCatalog();
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
         * Тестовый Clock фиксирован. Чат создаётся на секунду
         * раньше времени, которое сервис использует как updatedAt.
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

    private List<RaceOutcome> race(
            Callable<ChatProcessingContext> first,
            Callable<ChatProcessingContext> second
    ) throws Exception {

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<RaceOutcome> firstFuture =
                    executor.submit(
                            () -> run(
                                    ready,
                                    start,
                                    first
                            )
                    );

            Future<RaceOutcome> secondFuture =
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

                RaceOutcome firstOutcome =
                        firstFuture.get(
                                RESULT_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        );

                RaceOutcome secondOutcome =
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
         * Разблокирует задания, если сбой произошёл
         * до обычного start.countDown().
         */
        start.countDown();

        /*
         * Прерывает задания, которые могли зависнуть.
         * try-with-resources закроет executor после выхода.
         */
        executor.shutdownNow();
    }

    private RaceOutcome run(
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
                return new RaceFailure(
                        new IllegalStateException(
                                "Истекло время ожидания старта race"
                        )
                );
            }

            return new RaceSuccess(
                    action.call()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new RaceFailure(
                    exception
            );
        } catch (Exception exception) {
            /*
             * Ожидаемый конкурентный конфликт возвращается
             * управляющему потоку как результат race.
             */
            return new RaceFailure(
                    exception
            );
        }
    }

    private static void assertRaceOutcomes(
            List<RaceOutcome> outcomes,
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
                        RaceSuccess.class::isInstance
                )
                .count();

        long expectedFailureCount = outcomes.stream()
                .filter(
                        outcome ->
                                outcome instanceof RaceFailure(
                                        Throwable failure
                                )
                                        && expectedFailureType.isInstance(
                                        failure
                                )
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
                        outcome instanceof RaceSuccess
                                || outcome instanceof RaceFailure(
                                Throwable failure
                        ) && expectedFailureType.isInstance(
                                failure
                        )
                );
    }

    private static String describeOutcomes(
            List<RaceOutcome> outcomes
    ) {
        return outcomes.stream()
                .map(
                        ChatConcurrencyIntegrationTest
                                ::describeOutcome
                )
                .collect(
                        Collectors.joining(
                                ", ",
                                "[",
                                "]"
                        )
                );
    }

    private static String describeOutcome(
            RaceOutcome outcome
    ) {
        if (outcome instanceof RaceSuccess) {
            return "success";
        }

        if (outcome instanceof RaceFailure(
                Throwable failure
        )) {
            String message = failure.getMessage();

            return message == null
                    ? "failure without message"
                    : "failure: " + message;
        }

        throw new IllegalStateException(
                "Unknown race outcome"
        );
    }

    private void assertSingleAllowedRouteBoundToMockCatalog() {
        Long linked = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from public.chat_turns turn_row
                  join public.model_route_decisions route
                    on route.id = turn_row.model_route_decision_id
                  join public.model_catalog_entries catalog
                    on catalog.id = route.selected_catalog_entry_id
                   and catalog.version = route.selected_catalog_version
                 where route.outcome = 'ALLOWED'
                   and route.selected_model_key = ?
                   and route.selected_provider = 'mock'
                   and route.selected_provider_model_id = 'mock-safeai'
                   and catalog.model_key = ?
                   and catalog.provider = 'mock'
                   and catalog.provider_model_id = 'mock-safeai'
                   and catalog.lifecycle = 'ACTIVE'
                """,
                Long.class,
                MOCK_MODEL_KEY,
                MOCK_MODEL_KEY
        );

        assertThat(linked)
                .as("Ровно один ChatTurn должен ссылаться на ALLOWED route и immutable catalog snapshot")
                .isEqualTo(1L);
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

    private sealed interface RaceOutcome
            permits RaceSuccess, RaceFailure {
    }

    private record RaceSuccess(
            ChatProcessingContext context
    ) implements RaceOutcome {
    }

    private record RaceFailure(
            Throwable cause
    ) implements RaceOutcome {
    }
}

