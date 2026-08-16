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

    @Autowired
    private ChatTurnReservationService reservationService;

    @Autowired
    private Clock clock;

    @BeforeEach
    void alignPrimaryChatWithTestClock() {
        alignChatTimestamps(CHAT_ID);
    }

    @Test
    void concurrentDuplicateRequestsCreateOneUserMessageAndOneTurn()
            throws Exception {

        assertOwnedChatVisible(CHAT_ID);

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
    }

    @Test
    void differentClientRequestIdsInOneChatCannotProcessInParallel()
            throws Exception {

        assertOwnedChatVisible(CHAT_ID);

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
             * Ожидаемый конкурентный конфликт возвращается
             * управляющему потоку как результат race.
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