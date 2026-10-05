package ru.safeai.gateway.model.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.chat.integration.AbstractChatPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast schema smoke coverage for the V64/V65 control-plane additions.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.usage.rollup.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelControlPlaneSchemaV64V65PostgresIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    private static final String PREPARED_BINDING_TABLE =
            "model_route_prepared_requests_v62";

    @Test
    void v64DurableEvidenceTablesExist() {
        assertThat(regclass("public.model_route_budget_commitments_v64"))
                .isEqualTo("model_route_budget_commitments_v64");
        assertThat(regclass("public.knowledge_rag_context_receipts_v64"))
                .isEqualTo("knowledge_rag_context_receipts_v64");
        assertThat(regclass("public.model_route_prepared_requests_v62"))
                .isEqualTo("model_route_prepared_requests_v62");
    }

    @Test
    void v64PreparedBindingColumnsAreInstalled() {
        assertThat(preparedBindingColumnExists("knowledge_base_id"))
                .isTrue();

        assertThat(preparedBindingColumnExists("retrieval_run_id"))
                .isTrue();

        assertThat(preparedBindingColumnExists("rag_context_sha256"))
                .isTrue();

        assertThat(preparedBindingColumnExists("binding_version"))
                .isTrue();
    }

    @Test
    void v64PreparedGuardAndV65PhysicalAttemptGuardAreInstalled() {
        assertThat(triggerCount(
                "trg_model_route_prepared_requests_guard_v64"
        )).isEqualTo(1);

        assertThat(triggerCount(
                "trg_execution_attempt_prepared_binding_v65"
        )).isEqualTo(1);

        assertThat(triggerCount(
                "trg_model_route_prepared_requests_guard_v62"
        )).isZero();
    }

    @Test
    void v65PolicyBindingConstraintExistsWithoutRewritingHistory() {
        Boolean validated =
                jdbcTemplate.queryForObject(
                        """
                        select convalidated
                        from pg_constraint
                        where conname =
                            'chk_model_route_decisions_v3_policy_binding_v65'
                        """,
                        Boolean.class
                );

        /*
         * V65 deliberately installs this as NOT VALID: PostgreSQL enforces it
         * for new rows while immutable historical evidence is not rewritten.
         */
        assertThat(validated)
                .isFalse();
    }

    private String regclass(String relation) {
        return jdbcTemplate.queryForObject(
                "select to_regclass(?)::text",
                String.class,
                relation
        );
    }

    private boolean preparedBindingColumnExists(
            String column
    ) {
        Boolean result =
                jdbcTemplate.queryForObject(
                        """
                        select exists (
                            select 1
                            from information_schema.columns
                            where table_schema = 'public'
                              and table_name = ?
                              and column_name = ?
                        )
                        """,
                        Boolean.class,
                        PREPARED_BINDING_TABLE,
                        column
                );

        return Boolean.TRUE.equals(result);
    }

    private int triggerCount(String triggerName) {
        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from pg_trigger
                        where tgname = ?
                          and not tgisinternal
                        """,
                        Integer.class,
                        triggerName
                );

        return count == null ? 0 : count;
    }
}
