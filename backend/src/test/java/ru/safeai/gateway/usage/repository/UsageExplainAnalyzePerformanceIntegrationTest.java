package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("performance")
@SpringBootTest(
        properties = "safeai.usage.rollup.enabled=false"
)
@ActiveProfiles("test")
@EnabledIfSystemProperty(
        named = "safeai.usage.performance",
        matches = "true"
)
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class UsageExplainAnalyzePerformanceIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    private static final String EXPLAIN_ANALYZE_PREFIX =
            "explain (analyze, buffers, format text) ";

    private static final Pattern EXECUTION_TIME =
            Pattern.compile(
                    "Execution Time: ([0-9.]+) ms"
            );

    private static final Instant FROM =
            Instant.parse("2026-06-01T00:00:00Z");

    private static final Instant TO =
            Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void explainAnalyzeCoversGlobalModelOrganizationAndUserShapes()
            throws IOException {
        seedLiveMessages();

        Map<String, ExplainCase> cases =
                createExplainCases();

        long slaMillis = Long.getLong(
                "safeai.usage.explain-sla-ms",
                2_000L
        );

        Path outputDirectory = Path.of(
                "target",
                "usage-explain"
        );

        Files.createDirectories(outputDirectory);

        for (Map.Entry<String, ExplainCase> entry
                : cases.entrySet()) {
            String plan = explainAnalyze(
                    entry.getValue()
            );

            Files.writeString(
                    outputDirectory.resolve(
                            entry.getKey() + ".txt"
                    ),
                    plan
            );

            assertThat(plan)
                    .contains("Execution Time")
                    .contains("Buffers:");

            assertThat(executionTimeMillis(plan))
                    .as(
                            entry.getKey()
                                    + " execution time"
                    )
                    .isLessThanOrEqualTo(
                            (double) slaMillis
                    );
        }
    }

    private Map<String, ExplainCase> createExplainCases() {
        Map<String, ExplainCase> cases =
                new LinkedHashMap<>();

        cases.put(
                "global-no-model",
                new ExplainCase(
                        """
                        select
                            coalesce(
                                m.model,
                                '__unattributed__'
                            ),
                            count(*)
                        from public.chat_messages m
                        where m.role = 'ASSISTANT'
                          and m.created_at >= ?
                          and m.created_at < ?
                        group by coalesce(
                            m.model,
                            '__unattributed__'
                        )
                        """,
                        List.of(
                                Timestamp.from(FROM),
                                Timestamp.from(TO)
                        )
                )
        );

        cases.put(
                "global-with-model",
                new ExplainCase(
                        """
                        select
                            coalesce(
                                m.model,
                                '__unattributed__'
                            ),
                            count(*)
                        from public.chat_messages m
                        where m.role = 'ASSISTANT'
                          and m.created_at >= ?
                          and m.created_at < ?
                          and m.model = ?
                        group by coalesce(
                            m.model,
                            '__unattributed__'
                        )
                        """,
                        List.of(
                                Timestamp.from(FROM),
                                Timestamp.from(TO),
                                "perf-model-1"
                        )
                )
        );

        cases.put(
                "organization",
                new ExplainCase(
                        """
                        select
                            coalesce(
                                m.model,
                                '__unattributed__'
                            ),
                            count(*)
                        from public.chat_messages m
                        where m.role = 'ASSISTANT'
                          and m.organization_id = ?
                          and m.created_at >= ?
                          and m.created_at < ?
                        group by coalesce(
                            m.model,
                            '__unattributed__'
                        )
                        """,
                        List.of(
                                ORGANIZATION_A_ID,
                                Timestamp.from(FROM),
                                Timestamp.from(TO)
                        )
                )
        );

        cases.put(
                "user",
                new ExplainCase(
                        """
                        select
                            coalesce(
                                m.model,
                                '__unattributed__'
                            ),
                            count(*)
                        from public.chat_sessions s
                        join public.chat_messages m
                          on m.session_id = s.id
                        where s.user_id = ?
                          and m.role = 'ASSISTANT'
                          and m.created_at >= ?
                          and m.created_at < ?
                        group by coalesce(
                            m.model,
                            '__unattributed__'
                        )
                        """,
                        List.of(
                                USER_A_ID,
                                Timestamp.from(FROM),
                                Timestamp.from(TO)
                        )
                )
        );

        return cases;
    }

    private void seedLiveMessages() {
        int rows = Integer.getInteger(
                "safeai.usage.performance.rows",
                100_000
        );

        jdbcTemplate.update(
                """
                insert into public.chat_messages (
                    id,
                    session_id,
                    organization_id,
                    role,
                    content,
                    model,
                    input_tokens,
                    output_tokens,
                    cost_usd,
                    status,
                    created_at,
                    usage_status,
                    pricing_status,
                    currency,
                    pricing_version,
                    pricing_calculated_at,
                    ai_response_status
                )
                select
                    md5(
                        'usage-performance-' || value
                    )::uuid,
                    ?::uuid,
                    ?::uuid,
                    'ASSISTANT',
                    'performance row',
                    'perf-model-' || (value % 10),
                    100,
                    50,
                    0,
                    'COMPLETED',
                    '2026-06-01T00:00:00Z'::timestamptz
                        + (value % 2592000)
                        * interval '1 second',
                    'AVAILABLE',
                    'FREE',
                    'USD',
                    'performance-v1',
                    '2026-06-01T00:00:00Z'::timestamptz
                        + (value % 2592000)
                        * interval '1 second',
                    'COMPLETED'
                from generate_series(1, ?) value
                """,
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                rows
        );

        jdbcTemplate.execute(
                "analyze public.chat_messages"
        );
    }

    /*
     * SQL-шаблоны ExplainCase определены только внутри этого тестового
     * класса и не формируются из пользовательского ввода. Все изменяемые
     * значения передаются как параметры PreparedStatement.
     */
    @SuppressWarnings("SqlSourceToSinkFlow")
    private String explainAnalyze(
            ExplainCase explainCase
    ) {
        String explainSql =
                EXPLAIN_ANALYZE_PREFIX
                        + explainCase.sql();

        return jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> {
                    try (PreparedStatement statement =
                                 connection.prepareStatement(
                                         explainSql
                                 )) {
                        bindParameters(
                                statement,
                                explainCase.parameters()
                        );

                        return readPlan(statement);
                    }
                }
        );
    }

    private void bindParameters(
            PreparedStatement statement,
            List<Object> parameters
    ) throws java.sql.SQLException {
        for (int index = 0;
             index < parameters.size();
             index++) {
            statement.setObject(
                    index + 1,
                    parameters.get(index)
            );
        }
    }

    private String readPlan(
            PreparedStatement statement
    ) throws java.sql.SQLException {
        List<String> lines = new ArrayList<>();

        try (ResultSet resultSet =
                     statement.executeQuery()) {
            while (resultSet.next()) {
                lines.add(
                        resultSet.getString(1)
                );
            }
        }

        return String.join(
                "\n",
                lines
        );
    }

    private double executionTimeMillis(
            String plan
    ) {
        Matcher matcher =
                EXECUTION_TIME.matcher(plan);

        if (!matcher.find()) {
            throw new IllegalStateException(
                    "EXPLAIN ANALYZE не содержит "
                            + "Execution Time"
            );
        }

        return Double.parseDouble(
                matcher.group(1)
        );
    }

    private record ExplainCase(
            String sql,
            List<Object> parameters
    ) {

        private ExplainCase {
            if (sql == null || sql.isBlank()) {
                throw new IllegalArgumentException(
                        "sql не должен быть пустым"
                );
            }

            sql = sql.trim();

            parameters = List.copyOf(
                    Objects.requireNonNull(
                            parameters,
                            "parameters не должен быть null"
                    )
            );
        }
    }
}