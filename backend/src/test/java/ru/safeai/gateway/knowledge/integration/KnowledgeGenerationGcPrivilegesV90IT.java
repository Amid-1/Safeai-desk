package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V90: the application has no direct DELETE privilege for knowledge chunks.
 * Only the fenced generation-GC SECURITY DEFINER function may perform the purge.
 * Assertions validate PostgreSQL SQLSTATE, not Spring's translated message.
 */
@ActiveProfiles("test")
@SpringBootTest
class KnowledgeGenerationGcPrivilegesV90IT extends AbstractPostgresIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String CHECK_VIOLATION = "23514";

    @Test
    void applicationHasNoDirectDeleteAndCannotPurgeAnUnknownGeneration() {
        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), "safeai_app", "test-only-app-password"));

        // Verify the real login, not a migrator/admin connection masquerading as app.
        assertThat(app.queryForObject("SELECT session_user", String.class))
            .isEqualTo("safeai_app");
        assertThat(app.queryForObject("""
            SELECT has_table_privilege(current_user,
                'public.knowledge_document_chunks', 'DELETE')
            """, Boolean.class)).isFalse();
        assertThat(app.queryForObject("""
            SELECT has_function_privilege(current_user,
                'public.gc_knowledge_generation_chunks_v90(uuid,uuid)', 'EXECUTE')
            """, Boolean.class)).isTrue();

        // PostgreSQL refuses even a DELETE whose predicate matches no rows.
        // Spring translates SQLSTATE 42501 into BadSqlGrammarException, whose
        // own message need not mention 'permission denied'. Inspect the PG cause.
        assertThatThrownBy(() -> app.update("""
            DELETE FROM public.knowledge_document_chunks WHERE false
            """))
            .isInstanceOf(DataAccessException.class)
            .satisfies(failure -> assertPostgresState(failure, INSUFFICIENT_PRIVILEGE));

        // Being allowed to EXECUTE the function must not allow arbitrary purge.
        // A random generation has no durable COLLECTING fence: V90 rejects it.
        assertThatThrownBy(() -> app.queryForObject("""
            SELECT public.gc_knowledge_generation_chunks_v90(?::uuid,?::uuid)
            """, Integer.class, UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class)
            .satisfies(failure -> assertPostgresState(failure, CHECK_VIOLATION));
    }

    private static void assertPostgresState(Throwable failure, String sqlState) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause)
            .as("The Spring exception must preserve the PostgreSQL root cause")
            .isInstanceOf(PSQLException.class);
        assertThat(((PSQLException) cause).getSQLState())
            .as("The DB must reject the statement for the intended reason")
            .isEqualTo(sqlState);
    }
}
