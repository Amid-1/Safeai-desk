package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные проверки PostgreSQL session-level advisory lock для retention.
 *
 * <p>Два экземпляра сервиса используют один пул, но выполняются в разных
 * потоках и запрашивают независимые JDBC-сессии. Это проверяет взаимное
 * исключение на уровне PostgreSQL; полноценный тест двух JVM проводится отдельно.</p>
 */
@SpringBootTest(
        classes = ru.safeai.gateway.SafeaiBackendApplication.class,
        properties = "spring.datasource.hikari.maximum-pool-size=4")
@ActiveProfiles("test")
class AuditRetentionLockIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    private static final long TIMEOUT_SECONDS = 15;

    @Autowired
    private AuditRetentionLockService lockService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void onlyOneApplicationInstanceCanHoldRetentionLock() throws Exception {
        CountDownLatch firstActionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstAction = new CountDownLatch(1);
        AtomicBoolean secondActionExecuted = new AtomicBoolean(false);

        // Второй экземпляр использует настоящую реализацию, а не Mockito.
        AuditRetentionLockService anotherInstance =
                new AuditRetentionLockService(jdbcTemplate);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<AuditRetentionLockService.LockExecution<String>> first =
                        executor.submit(() -> lockService.tryExecute(() -> {
                            firstActionStarted.countDown();
                            awaitLatch(releaseFirstAction);
                            return "first";
                        }));

                assertThat(firstActionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("Первый исполнитель должен получить блокировку")
                        .isTrue();

                Future<AuditRetentionLockService.LockExecution<String>> second =
                        executor.submit(() -> anotherInstance.tryExecute(() -> {
                            secondActionExecuted.set(true);
                            return "second";
                        }));

                AuditRetentionLockService.LockExecution<String> secondResult =
                        second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Потерявший гонку не должен запускать побочное действие.
                assertThat(secondResult.acquired()).isFalse();
                assertThat(secondResult.result()).isNull();
                assertThat(secondActionExecuted.get()).isFalse();

                releaseFirstAction.countDown();
                AuditRetentionLockService.LockExecution<String> firstResult =
                        first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(firstResult.acquired()).isTrue();
                assertThat(firstResult.result()).isEqualTo("first");

                // После штатного unlock блокировку обязан получить другой экземпляр.
                AuditRetentionLockService.LockExecution<String> afterRelease =
                        anotherInstance.tryExecute(() -> "after-release");
                assertThat(afterRelease.acquired()).isTrue();
                assertThat(afterRelease.result()).isEqualTo("after-release");
            } finally {
                releaseFirstAction.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void actionFailurePropagatesAndReleasesAdvisoryLock() {
        IllegalStateException expected = new IllegalStateException("test-action-failure");

        // Исключение бизнес-действия не должно оставлять session-level lock в пуле.
        assertThatThrownBy(() -> lockService.tryExecute(() -> {
            throw expected;
        })).isSameAs(expected);

        AuditRetentionLockService anotherInstance =
                new AuditRetentionLockService(jdbcTemplate);
        AuditRetentionLockService.LockExecution<String> result =
                anotherInstance.tryExecute(() -> "recovered");
        assertThat(result.acquired()).isTrue();
        assertThat(result.result()).isEqualTo("recovered");
    }

    @Test
    void nullActionIsRejectedBeforeAnyLockAttempt() {
        assertThatThrownBy(() -> lockService.tryExecute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Превышено время ожидания тестовой блокировки");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание блокировки прервано", exception);
        }
    }
}
