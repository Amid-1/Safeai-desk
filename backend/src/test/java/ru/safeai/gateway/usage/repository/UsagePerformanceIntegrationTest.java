package ru.safeai.gateway.usage.repository;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.service.UsageReportExecutor;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("performance")
@SpringBootTest(properties = {
        "safeai.usage.rollup.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=1"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(
        named = "safeai.usage.performance",
        matches = "true"
)
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class UsagePerformanceIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    private static final int MAX_CONCURRENT_REPORTS = 4;
    private static final int TOTAL_REQUESTS = 12;

    private static final LocalDate FIRST_DATE =
            LocalDate.of(2025, 6, 1);

    private static final LocalDate LAST_DATE_EXCLUSIVE =
            FIRST_DATE.plusDays(366);

    @Autowired
    private UsageQueryRepository repository;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void seedProductionSizedRollups() {
        int models = Integer.getInteger(
                "safeai.usage.performance.models",
                100
        );

        insertOrganizationRollups(models);
        insertUserRollups(models);
        updateRollupWatermark();
    }

    @Test
    void rollupBackedReportsFor366DaysStayWithinConfiguredSla() {
        long slaMillis = Long.getLong(
                "safeai.usage.sla-ms",
                2_000L
        );

        UsageQueryCriteria criteria = criteria(
                ORGANIZATION_A_ID,
                null,
                null,
                utcStart(FIRST_DATE),
                utcStart(LAST_DATE_EXCLUSIVE)
        );

        UsageQueryPlan plan = new UsageQueryPlan(
                FIRST_DATE,
                LAST_DATE_EXCLUSIVE,
                List.of()
        );

        warmUpReports(
                criteria,
                plan
        );

        long startedAt = System.nanoTime();

        assertThat(
                repository.findModels(
                        criteria,
                        plan
                )
        ).isNotEmpty();

        assertThat(
                repository.findDaily(
                        criteria,
                        plan
                )
        ).hasSize(366);

        assertThat(
                repository.findSummary(
                        criteria,
                        plan,
                        PageRequest.of(0, 200)
                ).getContent()
        ).isNotEmpty();

        long elapsedMillis = Duration.ofNanos(
                System.nanoTime() - startedAt
        ).toMillis();

        assertThat(elapsedMillis)
                .as("366-day rollup report SLA")
                .isLessThanOrEqualTo(slaMillis);
    }

    @Test
    void concurrentAdmissionControlProtectsDatabasePool()
            throws Exception {
        UsageProperties properties =
                createUsageProperties();

        UsageReportExecutor reportExecutor =
                new UsageReportExecutor(
                        properties,
                        new SimpleMeterRegistry()
                );

        UsageQueryCriteria criteria = criteria(
                ORGANIZATION_A_ID,
                null,
                null,
                utcStart(FIRST_DATE),
                utcStart(LAST_DATE_EXCLUSIVE)
        );

        UsageQueryPlan plan = new UsageQueryPlan(
                FIRST_DATE,
                LAST_DATE_EXCLUSIVE,
                List.of()
        );

        CountDownLatch start =
                new CountDownLatch(1);

        CountDownLatch admitted =
                new CountDownLatch(
                        MAX_CONCURRENT_REPORTS
                );

        CountDownLatch admissionDecided =
                new CountDownLatch(
                        TOTAL_REQUESTS
                );

        CountDownLatch releaseAdmitted =
                new CountDownLatch(1);

        AtomicInteger completed =
                new AtomicInteger();

        AtomicInteger rejected =
                new AtomicInteger();

        List<Future<?>> futures =
                new ArrayList<>();

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(
                             TOTAL_REQUESTS
                     )) {
            try {
                submitConcurrentRequests(
                        executor,
                        futures,
                        reportExecutor,
                        criteria,
                        plan,
                        start,
                        admitted,
                        admissionDecided,
                        releaseAdmitted,
                        completed,
                        rejected
                );

                start.countDown();

                assertThat(
                        admitted.await(
                                10,
                                TimeUnit.SECONDS
                        )
                )
                        .as("Four reports should be admitted")
                        .isTrue();

                assertThat(
                        admissionDecided.await(
                                10,
                                TimeUnit.SECONDS
                        )
                )
                        .as("Every admission decision should complete")
                        .isTrue();

                releaseAdmitted.countDown();

                waitForFutures(futures);
            } finally {
                start.countDown();
                releaseAdmitted.countDown();

                executor.shutdownNow();

                assertThat(
                        executor.awaitTermination(
                                10,
                                TimeUnit.SECONDS
                        )
                )
                        .as("Performance executor should terminate")
                        .isTrue();
            }
        }

        assertThat(completed.get())
                .isEqualTo(MAX_CONCURRENT_REPORTS);

        assertThat(rejected.get())
                .isEqualTo(
                        TOTAL_REQUESTS
                                - MAX_CONCURRENT_REPORTS
                );

        assertThat(
                completed.get() + rejected.get()
        ).isEqualTo(TOTAL_REQUESTS);

        HikariDataSource hikari =
                dataSource.unwrap(
                        HikariDataSource.class
                );

        awaitPoolIdle(hikari);

        assertThat(
                hikari.getHikariPoolMXBean()
                        .getActiveConnections()
        ).isZero();

        assertThat(
                hikari.getHikariPoolMXBean()
                        .getTotalConnections()
        ).isLessThanOrEqualTo(6);
    }

    private void insertOrganizationRollups(
            int models
    ) {
        jdbcTemplate.update(
                """
                insert into public.usage_daily_org_model_rollups (
                    usage_date,
                    organization_id,
                    model,
                    input_tokens,
                    output_tokens,
                    total_tokens,
                    cost_usd,
                    assistant_message_count,
                    failed_message_count,
                    completed_response_count,
                    refused_response_count,
                    incomplete_response_count,
                    partial_input_tokens,
                    partial_output_tokens,
                    available_usage_message_count,
                    partial_usage_message_count,
                    missing_usage_message_count,
                    usage_not_applicable_message_count,
                    priced_message_count,
                    free_message_count,
                    unpriced_message_count,
                    pricing_failed_message_count,
                    pricing_not_applicable_message_count,
                    created_at,
                    updated_at
                )
                select
                    day_value::date,
                    ?::uuid,
                    'model-' || model_number,
                    1000,
                    500,
                    1500,
                    1.000000000000,
                    10,
                    0,
                    10,
                    0,
                    0,
                    0,
                    0,
                    10,
                    0,
                    0,
                    0,
                    10,
                    0,
                    0,
                    0,
                    0,
                    current_timestamp,
                    current_timestamp
                from generate_series(
                    ?::date,
                    (?::date - interval '1 day')::date,
                    interval '1 day'
                ) day_value
                cross join generate_series(
                    1,
                    ?
                ) model_number
                """,
                ORGANIZATION_A_ID,
                Date.valueOf(FIRST_DATE),
                Date.valueOf(LAST_DATE_EXCLUSIVE),
                models
        );
    }

    private void insertUserRollups(
            int models
    ) {
        jdbcTemplate.update(
                """
                insert into public.usage_daily_user_model_rollups (
                    usage_date,
                    organization_id,
                    user_id,
                    model,
                    input_tokens,
                    output_tokens,
                    total_tokens,
                    cost_usd,
                    assistant_message_count,
                    failed_message_count,
                    completed_response_count,
                    refused_response_count,
                    incomplete_response_count,
                    partial_input_tokens,
                    partial_output_tokens,
                    available_usage_message_count,
                    partial_usage_message_count,
                    missing_usage_message_count,
                    usage_not_applicable_message_count,
                    priced_message_count,
                    free_message_count,
                    unpriced_message_count,
                    pricing_failed_message_count,
                    pricing_not_applicable_message_count,
                    created_at,
                    updated_at
                )
                select
                    day_value::date,
                    ?::uuid,
                    ?::uuid,
                    'model-' || model_number,
                    1000,
                    500,
                    1500,
                    1.000000000000,
                    10,
                    0,
                    10,
                    0,
                    0,
                    0,
                    0,
                    10,
                    0,
                    0,
                    0,
                    10,
                    0,
                    0,
                    0,
                    0,
                    current_timestamp,
                    current_timestamp
                from generate_series(
                    ?::date,
                    (?::date - interval '1 day')::date,
                    interval '1 day'
                ) day_value
                cross join generate_series(
                    1,
                    ?
                ) model_number
                """,
                ORGANIZATION_A_ID,
                USER_A_ID,
                Date.valueOf(FIRST_DATE),
                Date.valueOf(LAST_DATE_EXCLUSIVE),
                models
        );
    }

    private void updateRollupWatermark() {
        jdbcTemplate.update(
                """
                update public.usage_rollup_state
                set last_completed_date = ?,
                    updated_at = current_timestamp
                where job_name = 'usage-daily-rollup'
                """,
                Date.valueOf(
                        LAST_DATE_EXCLUSIVE.minusDays(1)
                )
        );
    }

    private void warmUpReports(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    ) {
        repository.findModels(
                criteria,
                plan
        );

        repository.findDaily(
                criteria,
                plan
        );

        repository.findSummary(
                criteria,
                plan,
                PageRequest.of(0, 200)
        );
    }

    private UsageProperties createUsageProperties() {
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

    private void submitConcurrentRequests(
            ExecutorService executor,
            List<Future<?>> futures,
            UsageReportExecutor reportExecutor,
            UsageQueryCriteria criteria,
            UsageQueryPlan plan,
            CountDownLatch start,
            CountDownLatch admitted,
            CountDownLatch admissionDecided,
            CountDownLatch releaseAdmitted,
            AtomicInteger completed,
            AtomicInteger rejected
    ) {
        for (int index = 0;
             index < TOTAL_REQUESTS;
             index++) {
            Future<?> future = executor.submit(() ->
                    executeConcurrentRequest(
                            reportExecutor,
                            criteria,
                            plan,
                            start,
                            admitted,
                            admissionDecided,
                            releaseAdmitted,
                            completed,
                            rejected
                    )
            );

            futures.add(future);
        }
    }

    private void executeConcurrentRequest(
            UsageReportExecutor reportExecutor,
            UsageQueryCriteria criteria,
            UsageQueryPlan plan,
            CountDownLatch start,
            CountDownLatch admitted,
            CountDownLatch admissionDecided,
            CountDownLatch releaseAdmitted,
            AtomicInteger completed,
            AtomicInteger rejected
    ) {
        awaitLatch(
                start,
                "Start latch timeout"
        );

        try {
            reportExecutor.execute(
                    "performance-summary",
                    () -> {
                        admitted.countDown();
                        admissionDecided.countDown();

                        awaitLatch(
                                releaseAdmitted,
                                "Release latch timeout"
                        );

                        return repository.findSummary(
                                criteria,
                                plan,
                                PageRequest.of(0, 200)
                        );
                    }
            );

            completed.incrementAndGet();
        } catch (ResponseStatusException exception) {
            assertThat(exception.getStatusCode())
                    .isEqualTo(
                            HttpStatus.TOO_MANY_REQUESTS
                    );

            rejected.incrementAndGet();
            admissionDecided.countDown();
        }
    }

    private void waitForFutures(
            List<Future<?>> futures
    ) throws Exception {
        for (Future<?> future : futures) {
            future.get(
                    30,
                    TimeUnit.SECONDS
            );
        }
    }

    private void awaitLatch(
            CountDownLatch latch,
            String timeoutMessage
    ) {
        try {
            if (!latch.await(
                    10,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        timeoutMessage
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Interrupted while waiting "
                            + "in performance test",
                    exception
            );
        }
    }

    private void awaitPoolIdle(
            HikariDataSource hikari
    ) {
        org.awaitility.Awaitility.await()
                .atMost(
                        Duration.ofSeconds(5)
                )
                .pollInterval(
                        Duration.ofMillis(25)
                )
                .untilAsserted(() ->
                        assertThat(
                                hikari.getHikariPoolMXBean()
                                        .getActiveConnections()
                        ).isZero()
                );
    }
}