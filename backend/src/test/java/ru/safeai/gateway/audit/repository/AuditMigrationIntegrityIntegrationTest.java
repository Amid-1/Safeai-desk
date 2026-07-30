package ru.safeai.gateway.audit.repository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AuditMigrationIntegrityIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void v26ColumnsAndConstraintsExist() {
        assertThat(columnExists(
                "audit_events",
                "actor_organization_id"
        )).isTrue();

        assertThat(columnExists(
                "audit_outbox",
                "actor_organization_id"
        )).isTrue();

        assertThat(columnExists(
                "audit_outbox",
                "occurred_at"
        )).isTrue();

        assertThat(columnExists(
                "audit_outbox",
                "attempt_count"
        )).isTrue();

        assertThat(columnExists(
                "audit_outbox",
                "next_attempt_at"
        )).isTrue();

        assertThat(columnExists(
                "audit_outbox",
                "last_error"
        )).isTrue();

        assertThat(columnExists(
                "audit_outbox",
                "dead_lettered_at"
        )).isTrue();

        assertThat(constraintExists(
                "audit_events",
                "chk_audit_events_details_size"
        )).isTrue();

        assertThat(constraintExists(
                "audit_outbox",
                "chk_audit_outbox_details_size"
        )).isTrue();

        assertThat(constraintExists(
                "audit_outbox",
                "chk_audit_outbox_delivery_state"
        )).isTrue();
    }

    @Test
    void v27ProductionIndexesExist() {
        assertThat(indexExists(
                "idx_audit_events_org_created_id_desc"
        )).isTrue();

        assertThat(indexExists(
                "idx_audit_events_org_type_created_id_desc"
        )).isTrue();

        assertThat(indexExists(
                "idx_audit_events_org_actor_user_created_id_desc"
        )).isTrue();

        assertThat(indexExists(
                "idx_audit_events_org_actor_email_pattern_created_id"
        )).isTrue();

        assertThat(indexExists(
                "idx_audit_outbox_ready"
        )).isTrue();

        assertThat(indexExists(
                "idx_audit_outbox_dead_letter"
        )).isTrue();
    }

    private boolean columnExists(
            String table,
            String column
    ) {
        return queryInt(
                """
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = ?
                  and column_name = ?
                """,
                table,
                column
        ) == 1;
    }

    private boolean constraintExists(
            String table,
            String constraint
    ) {
        return queryInt(
                """
                select count(*)
                from pg_constraint
                where conrelid =
                    to_regclass('public.' || ?)
                  and conname = ?
                """,
                table,
                constraint
        ) == 1;
    }

    private boolean indexExists(String index) {
        return queryInt(
                """
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and indexname = ?
                """,
                index
        ) == 1;
    }
}
