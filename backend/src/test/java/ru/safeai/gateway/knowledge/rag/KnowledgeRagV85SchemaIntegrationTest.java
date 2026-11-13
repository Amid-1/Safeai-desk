package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет реальные таблицы, столбцы и неизменяемые триггеры V85 в PostgreSQL.
 * Такой тест не заменяет привязку SQL Data Source в IntelliJ IDEA.
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
class KnowledgeRagV85SchemaIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void allV85RagTablesAndRequiredColumnsExist() {
        assertColumns("knowledge_rag_execution_permits_v85", Set.of(
                "id", "organization_id", "knowledge_base_id", "chat_turn_id",
                "retrieval_run_id", "context_sha256", "expires_at", "state", "completed_at"));
        assertColumns("knowledge_rag_execution_permit_sources_v85", Set.of(
                "permit_id", "organization_id", "knowledge_base_id",
                "document_id", "document_version_id"));
        assertColumns("knowledge_rag_execution_attempt_leases_v85", Set.of(
                "id", "permit_id", "provider_attempt_id", "attempt_number",
                "lease_token", "lease_until", "state", "completed_at",
                "error_code", "heartbeat_at"));
    }

    @Test
    void immutableEvidenceTriggersAreInstalledAndEnabled() {
        List<String> installed = jdbcTemplate.queryForList("""
                SELECT t.tgname
                  FROM pg_catalog.pg_trigger t
                  JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public'
                   AND NOT t.tgisinternal
                   AND t.tgenabled = 'O'
                   AND t.tgname IN ('trg_knowledge_rag_permit_v85',
                                    'trg_knowledge_rag_attempt_lease_v85')
                """, String.class);
        assertThat(installed).containsExactlyInAnyOrder(
                "trg_knowledge_rag_permit_v85", "trg_knowledge_rag_attempt_lease_v85");
    }

    private void assertColumns(String table, Set<String> requiredColumns) {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ?
                """, String.class, table);
        assertThat(columns)
                .as("Flyway V85 должен создать таблицу %s со всеми необходимыми столбцами", table)
                .containsAll(requiredColumns);
    }
}
