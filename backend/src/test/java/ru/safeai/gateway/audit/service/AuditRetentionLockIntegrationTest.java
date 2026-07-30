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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        properties =
                "spring.datasource.hikari.maximum-pool-size=4"
)
@ActiveProfiles("test")
class AuditRetentionLockIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private AuditRetentionLockService lockService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void onlyOneApplicationInstanceCanHoldRetentionLock()
            throws Exception {

        CountDownLatch firstActionStarted =
                new CountDownLatch(1);

        CountDownLatch releaseFirstAction =
                new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            try {
                Future<AuditRetentionLockService
                        .LockExecution<String>> first =
                        executor.submit(() ->
                                lockService.tryExecute(() -> {
                                    firstActionStarted
                                            .countDown();

                                    awaitLatch(
                                            releaseFirstAction
                                    );

                                    return "first";
                                })
                        );

                assertThat(
                        firstActionStarted.await(
                                TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                ).isTrue();

                Future<AuditRetentionLockService
                        .LockExecution<String>> second =
                        executor.submit(() ->
                                lockService.tryExecute(
                                        () -> "second"
                                )
                        );

                AuditRetentionLockService
                        .LockExecution<String>
                        secondResult =
                        second.get(
                                TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        );

                assertThat(secondResult.acquired())
                        .isFalse();

                assertThat(secondResult.result())
                        .isNull();

                releaseFirstAction.countDown();

                AuditRetentionLockService
                        .LockExecution<String>
                        firstResult =
                        first.get(
                                TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        );

                assertThat(firstResult.acquired())
                        .isTrue();

                assertThat(firstResult.result())
                        .isEqualTo("first");

                AuditRetentionLockService
                        .LockExecution<String>
                        afterRelease =
                        lockService.tryExecute(
                                () -> "after-release"
                        );

                assertThat(afterRelease.acquired())
                        .isTrue();

                assertThat(afterRelease.result())
                        .isEqualTo("after-release");
            } finally {
                releaseFirstAction.countDown();
            }
        }
    }

    private static void awaitLatch(
            CountDownLatch latch
    ) {
        try {
            if (!latch.await(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "Timed out waiting for latch"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Interrupted while waiting",
                    exception
            );
        }
    }
}
