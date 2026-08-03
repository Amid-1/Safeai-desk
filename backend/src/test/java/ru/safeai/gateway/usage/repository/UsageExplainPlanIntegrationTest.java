package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = "safeai.usage.rollup.enabled=false")
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class UsageExplainPlanIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    @Test
    void globalSummaryWithoutModelHasUsableDateModelIndexPlan() {
        String plan = explain(
                """
                select coalesce(m.model, '__unattributed__'), count(*)
                from public.chat_messages m
                where m.role = 'ASSISTANT'
                  and m.created_at >= ?
                  and m.created_at < ?
                group by coalesce(m.model, '__unattributed__')
                """,
                null,
                false
        );

        assertThat(plan)
                .contains("Index")
                .doesNotContain("Seq Scan on chat_messages");
    }

    @Test
    void globalSummaryWithModelHasUsableDateModelIndexPlan() {
        String plan = explain(
                """
                select coalesce(m.model, '__unattributed__'), count(*)
                from public.chat_messages m
                where m.role = 'ASSISTANT'
                  and m.created_at >= ?
                  and m.created_at < ?
                  and m.model = ?
                group by coalesce(m.model, '__unattributed__')
                """,
                null,
                true
        );

        assertThat(plan)
                .contains("Index")
                .doesNotContain("Seq Scan on chat_messages");
    }

    @Test
    void organizationSummaryHasUsableTenantDateModelIndexPlan() {
        String plan = explain(
                """
                select coalesce(m.model, '__unattributed__'), count(*)
                from public.chat_messages m
                where m.role = 'ASSISTANT'
                  and m.organization_id = ?
                  and m.created_at >= ?
                  and m.created_at < ?
                group by coalesce(m.model, '__unattributed__')
                """,
                ORGANIZATION_A_ID,
                false
        );

        assertThat(plan)
                .contains("Index")
                .doesNotContain("Seq Scan on chat_messages");
    }

    @Test
    void userSummaryHasUsableSessionDateModelIndexPlan() {
        String plan = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("set enable_seqscan = off");
                    }

                    try (PreparedStatement statement =
                                 connection.prepareStatement("""
                        explain (costs off, format text)
                        select coalesce(m.model, '__unattributed__'), count(*)
                        from public.chat_sessions s
                        join public.chat_messages m
                          on m.session_id = s.id
                        where s.user_id = ?
                          and m.role = 'ASSISTANT'
                          and m.created_at >= ?
                          and m.created_at < ?
                        group by coalesce(m.model, '__unattributed__')
                        """)) {
                        statement.setObject(1, USER_A_ID);
                        statement.setTimestamp(2, Timestamp.from(RANGE_FROM));
                        statement.setTimestamp(3, Timestamp.from(RANGE_TO));
                        return readPlan(statement);
                    } finally {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("set enable_seqscan = on");
                        }
                    }
                }
        );

        assertThat(plan)
                .contains("Index")
                .doesNotContain("Seq Scan on chat_messages");
    }

    private String explain(
            String query,
            UUID organizationId,
            boolean withModel
    ) {
        return jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("set enable_seqscan = off");
                    }

                    try (PreparedStatement statement =
                                 connection.prepareStatement(
                                         "explain (costs off, format text) "
                                                 + query
                                 )) {
                        int index = 1;

                        if (organizationId != null) {
                            statement.setObject(index++, organizationId);
                        }

                        statement.setTimestamp(
                                index++,
                                Timestamp.from(RANGE_FROM)
                        );
                        statement.setTimestamp(
                                index++,
                                Timestamp.from(RANGE_TO)
                        );

                        if (withModel) {
                            statement.setString(index, "model-a");
                        }

                        return readPlan(statement);
                    } finally {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("set enable_seqscan = on");
                        }
                    }
                }
        );
    }

    private String readPlan(
            PreparedStatement statement
    ) throws java.sql.SQLException {
        List<String> lines = new ArrayList<>();

        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                lines.add(resultSet.getString(1));
            }
        }

        return String.join("\n", lines);
    }
}
