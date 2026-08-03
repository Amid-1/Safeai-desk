package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@SpringBootTest(
        properties = "safeai.usage.rollup.enabled=false"
)
@ActiveProfiles("test")
class UsageRollupAdvisoryLockIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    private static final long LOCK_KEY = 9_260_802L;

    private static final long LATCH_TIMEOUT_SECONDS = 5L;
    private static final long FUTURE_TIMEOUT_SECONDS = 5L;
    private static final long TERMINATION_TIMEOUT_SECONDS = 5L;

    @Autowired
    private UsageRollupStateRepository stateRepository;

    @Test
    void onlyOneApplicationInstanceCanHoldRollupLock()
            throws Exception {
        CountDownLatch firstEntered =
                new CountDownLatch(1);

        CountDownLatch releaseFirst =
                new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newSingleThreadExecutor()) {
            try {
                Future<Boolean> first =
                        submitFirstLockHolder(
                                executor,
                                firstEntered,
                                releaseFirst
                        );

                assertThat(
                        firstEntered.await(
                                LATCH_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                )
                        .as("First lock holder should enter the action")
                        .isTrue();

                boolean second =
                        stateRepository.executeWithAdvisoryLock(
                                LOCK_KEY,
                                () -> {
                                    throw new AssertionError(
                                            "Second lock holder "
                                                    + "must not execute"
                                    );
                                }
                        );

                assertThat(second)
                        .isFalse();

                releaseFirst.countDown();

                assertThat(
                        first.get(
                                FUTURE_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
            } finally {
                releaseFirst.countDown();

                executor.shutdownNow();

                assertThat(
                        executor.awaitTermination(
                                TERMINATION_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                )
                        .as("ExecutorService should terminate")
                        .isTrue();
            }
        }
    }

    @Test
    void lockIsReleasedWhenRollupActionFails() {
        assertThatThrownBy(() ->
                stateRepository.executeWithAdvisoryLock(
                        LOCK_KEY,
                        () -> {
                            throw new IllegalStateException(
                                    "boom"
                            );
                        }
                )
        )
                .isInstanceOf(
                        InvalidDataAccessApiUsageException.class
                )
                .hasMessage("boom")
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage("boom");

        boolean acquiredAfterFailure =
                stateRepository.executeWithAdvisoryLock(
                        LOCK_KEY,
                        () -> {
                            // Проверяется успешное повторное получение lock.
                        }
                );

        assertThat(acquiredAfterFailure)
                .isTrue();
    }

    private Future<Boolean> submitFirstLockHolder(
            ExecutorService executor,
            CountDownLatch firstEntered,
            CountDownLatch releaseFirst
    ) {
        return executor.submit(() ->
                stateRepository.executeWithAdvisoryLock(
                        LOCK_KEY,
                        () -> {
                            firstEntered.countDown();
                            awaitFirstLockRelease(
                                    releaseFirst
                            );
                        }
                )
        );
    }

    private void awaitFirstLockRelease(
            CountDownLatch releaseFirst
    ) {
        try {
            boolean released = releaseFirst.await(
                    LATCH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!released) {
                throw new IllegalStateException(
                        "First lock holder release timeout"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Interrupted while waiting "
                            + "for first lock holder release",
                    exception
            );
        }
    }
}