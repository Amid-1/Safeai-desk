package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.usage.config.UsageProperties;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class UsageReportExecutorTest {

    private static final int MAX_CONCURRENT_REPORTS = 1;
    private static final long TIMEOUT_SECONDS = 5L;

    @Test
    void successfulReportRecordsTimerAndReleasesPermit() {
        SimpleMeterRegistry registry =
                new SimpleMeterRegistry();

        UsageReportExecutor executor =
                createExecutor(registry);

        String result = executor.execute(
                "summary",
                () -> "ok"
        );

        assertThat(result)
                .isEqualTo("ok");

        assertThat(
                registry.get(
                                "safeai.usage.report.duration"
                        )
                        .tag("report", "summary")
                        .timer()
                        .count()
        ).isEqualTo(1);

        assertThat(
                availablePermits(registry)
        ).isEqualTo(1.0);
    }

    @Test
    void concurrentLimitRejectsExcessReportWithoutBlockingPool()
            throws Exception {
        SimpleMeterRegistry registry =
                new SimpleMeterRegistry();

        UsageReportExecutor reportExecutor =
                createExecutor(registry);

        CountDownLatch entered =
                new CountDownLatch(1);

        CountDownLatch release =
                new CountDownLatch(1);

        try (ExecutorService pool =
                     Executors.newSingleThreadExecutor()) {
            Future<String> first;

            try {
                first = pool.submit(() ->
                        reportExecutor.execute(
                                "summary",
                                () -> {
                                    entered.countDown();
                                    awaitRelease(release);
                                    return "first";
                                }
                        )
                );

                assertThat(
                        entered.await(
                                TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                )
                        .as("First report should enter execution")
                        .isTrue();

                assertThatThrownBy(() ->
                        reportExecutor.execute(
                                "summary",
                                () -> "second"
                        )
                )
                        .isInstanceOf(
                                ResponseStatusException.class
                        )
                        .satisfies(exception ->
                                assertThat(
                                        ((ResponseStatusException) exception)
                                                .getStatusCode()
                                ).isEqualTo(
                                        HttpStatus.TOO_MANY_REQUESTS
                                )
                        );

                assertThat(
                        registry.get(
                                        "safeai.usage.reports.rejected"
                                )
                                .tag("report", "summary")
                                .counter()
                                .count()
                ).isEqualTo(1.0);

                release.countDown();

                assertThat(
                        first.get(
                                TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                ).isEqualTo("first");
            } finally {
                release.countDown();

                pool.shutdownNow();

                assertThat(
                        pool.awaitTermination(
                                TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                )
                        .as("ExecutorService should terminate")
                        .isTrue();
            }
        }

        assertThat(
                availablePermits(registry)
        ).isEqualTo(1.0);
    }

    @Test
    void timeoutAndGenericErrorsUseSeparateMetricsAndReleasePermits() {
        SimpleMeterRegistry registry =
                new SimpleMeterRegistry();

        UsageReportExecutor executor =
                createExecutor(registry);

        assertThatThrownBy(() ->
                executor.execute(
                        "daily",
                        () -> {
                            throw new QueryTimeoutException(
                                    "timeout"
                            );
                        }
                )
        )
                .isInstanceOf(
                        QueryTimeoutException.class
                )
                .hasMessage("timeout");

        assertThatThrownBy(() ->
                executor.execute(
                        "models",
                        () -> {
                            throw new IllegalStateException(
                                    "boom"
                            );
                        }
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage("boom");

        assertThat(
                registry.get(
                                "safeai.usage.reports.timeout"
                        )
                        .tag("report", "daily")
                        .counter()
                        .count()
        ).isEqualTo(1.0);

        assertThat(
                registry.get(
                                "safeai.usage.reports.error"
                        )
                        .tag("report", "models")
                        .counter()
                        .count()
        ).isEqualTo(1.0);

        assertThat(
                availablePermits(registry)
        ).isEqualTo(1.0);
    }

    @Test
    void invalidReportNameIsRejectedBeforeActionRuns() {
        SimpleMeterRegistry registry =
                new SimpleMeterRegistry();

        UsageReportExecutor executor =
                createExecutor(registry);

        assertThatThrownBy(() ->
                executor.execute(
                        "   ",
                        () -> "x"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining("report");

        assertThatThrownBy(() ->
                executor.execute(
                        "x".repeat(65),
                        () -> "x"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining("64");

        assertThat(
                availablePermits(registry)
        ).isEqualTo(1.0);
    }

    @Test
    void metricsFailureDoesNotFailReportOrLeakPermit() {
        MeterRegistry brokenRegistry =
                mock(MeterRegistry.class);

        UsageReportExecutor executor =
                new UsageReportExecutor(
                        createProperties(),
                        brokenRegistry
                );

        assertThat(
                executor.execute(
                        "summary",
                        () -> "first"
                )
        ).isEqualTo("first");

        assertThat(
                executor.execute(
                        "summary",
                        () -> "second"
                )
        ).isEqualTo("second");
    }

    private UsageReportExecutor createExecutor(
            SimpleMeterRegistry registry
    ) {
        return new UsageReportExecutor(
                createProperties(),
                registry
        );
    }

    private UsageProperties createProperties() {
        return new UsageProperties(
                Duration.ofDays(30),
                Duration.ofDays(366),
                Duration.ofDays(31),
                200,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                200,
                MAX_CONCURRENT_REPORTS,
                UsageProperties.Rollup.defaults()
        );
    }

    private double availablePermits(
            SimpleMeterRegistry registry
    ) {
        return registry.get(
                        "safeai.usage.reports.available_permits"
                )
                .gauge()
                .value();
    }

    private void awaitRelease(
            CountDownLatch release
    ) {
        try {
            boolean released = release.await(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!released) {
                throw new IllegalStateException(
                        "Report release latch timeout"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Interrupted while waiting "
                            + "for report release",
                    exception
            );
        }
    }
}