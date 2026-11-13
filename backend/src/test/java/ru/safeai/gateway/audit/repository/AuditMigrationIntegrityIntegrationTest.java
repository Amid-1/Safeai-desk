package ru.safeai.gateway.audit.repository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет фактическое применение миграций аудита V26 и V27 к PostgreSQL.
 *
 * <p>Контекст Spring Boot и Flyway остаются настоящими, без подмены DataSource
 * и отключения миграций. При ошибке запуска контекста необходимо анализировать
 * ПЕРВОЕ исключение «Caused by», а не сообщение о пороге повторных запусков.</p>
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
class AuditMigrationIntegrityIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    // Отключаем только фоновую отправку аудита; сам журнал и таблицы не подменяем.
    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void v26ColumnsAndConstraintsExist() {
        for (String column : new String[]{
                "actor_organization_id"
        }) {
            assertThat(columnExists("audit_events", column))
                    .as("Отсутствует столбец public.audit_events.%s из V26", column)
                    .isTrue();
        }

        for (String column : new String[]{
                "actor_organization_id",
                "occurred_at",
                "attempt_count",
                "next_attempt_at",
                "last_error",
                "dead_lettered_at"
        }) {
            assertThat(columnExists("audit_outbox", column))
                    .as("Отсутствует столбец public.audit_outbox.%s из V26", column)
                    .isTrue();
        }

        assertValidatedConstraint("audit_events", "chk_audit_events_details_size");
        assertValidatedConstraint("audit_outbox", "chk_audit_outbox_details_size");
        assertValidatedConstraint("audit_outbox", "chk_audit_outbox_delivery_state");
    }

    @Test
    void v27ProductionIndexesExist() {
        for (String index : new String[]{
                "idx_audit_events_org_created_id_desc",
                "idx_audit_events_org_type_created_id_desc",
                "idx_audit_events_org_actor_user_created_id_desc",
                "idx_audit_events_org_actor_email_pattern_created_id",
                "idx_audit_outbox_ready",
                "idx_audit_outbox_dead_letter"
        }) {
            // При CREATE INDEX CONCURRENTLY индекс может существовать, но быть
            // невалидным после сбоя. Одного pg_indexes здесь недостаточно.
            assertThat(indexIsReadyAndValid(index))
                    .as("Индекс V27 отсутствует, невалиден или не готов: %s", index)
                    .isTrue();
        }
    }

    private void assertValidatedConstraint(String table, String name) {
        assertThat(constraintIsValidated(table, name))
                .as("Ограничение V26 отсутствует или не прошло VALIDATE: %s.%s", table, name)
                .isTrue();
    }

    private boolean columnExists(String table, String column) {
        return queryInt("""
                select count(*)
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = ?
                   and column_name = ?
                """, table, column) == 1;
    }

    private boolean constraintIsValidated(String table, String constraint) {
        return queryInt("""
                select count(*)
                  from pg_catalog.pg_constraint
                 where conrelid = pg_catalog.to_regclass('public.' || ?)
                   and conname = ?
                   and convalidated
                """, table, constraint) == 1;
    }

    private boolean indexIsReadyAndValid(String indexName) {
        return queryInt("""
                select count(*)
                  from pg_catalog.pg_class index_class
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid = index_class.relnamespace
                  join pg_catalog.pg_index index_status
                    on index_status.indexrelid = index_class.oid
                 where namespace.nspname = 'public'
                   and index_class.relname = ?
                   and index_class.relkind in ('i', 'I')
                   and index_status.indisvalid
                   and index_status.indisready
                   and index_status.indislive
                """, indexName) == 1;
    }
}
