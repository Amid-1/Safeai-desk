package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.knowledge.config.KnowledgeErasureWorkerDatabaseConfig.WorkerConnections;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the REAL V82 row-lock contract under the isolated V89 database login. */
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeWorkerRowLockPrivilegesV89IntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void workerCanAcquireV82BaseRowLocksButCannotChangeBusinessFields() {
        UUID actor = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        insertUser(actor, PLATFORM_ORGANIZATION_ID, "worker-lock-" + actor + "@example.test",
                true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_bases
                    (id, organization_id, name, visibility, enabled, created_by_user_id, version)
                VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, base, PLATFORM_ORGANIZATION_ID, "V89 worker lock " + base, actor);

        try (WorkerConnections worker = new WorkerConnections(POSTGRES.getJdbcUrl(),
                "safeai_knowledge_erasure_worker", "test-only-knowledge-worker-password")) {
            assertThat(worker.jdbc().queryForObject("SELECT session_user", String.class))
                    .isEqualTo("safeai_knowledge_erasure_worker");
            new TransactionTemplate(worker.transactionManager()).executeWithoutResult(tx -> {
                assertThat(worker.jdbc().queryForObject("""
                        SELECT lifecycle_state FROM public.knowledge_bases
                        WHERE id=? AND organization_id=? FOR SHARE
                        """, String.class, base, PLATFORM_ORGANIZATION_ID)).isEqualTo("AVAILABLE");
                assertThat(worker.jdbc().queryForObject("""
                        SELECT lifecycle_state FROM public.knowledge_bases
                        WHERE id=? AND organization_id=? FOR UPDATE
                        """, String.class, base, PLATFORM_ORGANIZATION_ID)).isEqualTo("AVAILABLE");
            });
            // Roll back only the deliberately forbidden DML; PostgreSQL must reject it
            // before application triggers can be used to bypass the column boundary.
            assertThatThrownBy(() -> worker.jdbc().update("""
                    UPDATE public.knowledge_bases SET enabled=false WHERE id=?
                    """, base)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> worker.jdbc().update("""
                    UPDATE public.knowledge_bases SET name='FORGED' WHERE id=?
                    """, base)).isInstanceOf(DataAccessException.class);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT enabled FROM public.knowledge_bases WHERE id=?
                    """, Boolean.class, base)).isTrue();
        }
    }
}
