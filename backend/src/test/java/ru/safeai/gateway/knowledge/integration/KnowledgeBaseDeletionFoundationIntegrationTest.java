package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseDeletionResponse;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V82 populated-schema invariants and atomic parent/child acceptance. */
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeBaseDeletionFoundationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private AuditEventService audit;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void historicalFksUseImmutableBaseIdentityAndRawDeleteIsRejected() {
        for (String constraint : new String[]{
                "fk_chat_turns_knowledge_base_identity_v82",
                "fk_knowledge_retrieval_runs_base_identity_v82",
                "fk_knowledge_evaluation_runs_base_identity_v82"}) {
            String referenced = jdbcTemplate.queryForObject("""
                    SELECT confrelid::regclass::text FROM pg_constraint WHERE conname=?
                    """, String.class, constraint);
            assertThat(referenced).contains("knowledge_base_identities_v82");
        }
        UUID admin = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        insertUser(admin, PLATFORM_ORGANIZATION_ID,
                "base-deletion-" + admin + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases(id,organization_id,name,visibility,enabled,
                                            created_by_user_id,version)
                VALUES(?,? ,?,'ORGANIZATION',true,?,0)
                """, base, PLATFORM_ORGANIZATION_ID, "Protected " + base, admin);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM knowledge_bases WHERE id=?", base))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void parentAcceptanceAtomicallyQueuesAllChildrenAndRejectsNewWrites() {
        UUID admin = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        insertUser(admin, PLATFORM_ORGANIZATION_ID,
                "base-accept-" + admin + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases(id,organization_id,name,visibility,enabled,
                                            created_by_user_id,version)
                VALUES(?,? ,?,'ORGANIZATION',true,?,0)
                """, base, PLATFORM_ORGANIZATION_ID, "Accept " + base, admin);
        for (UUID doc : new UUID[]{doc1, doc2}) {
            jdbcTemplate.update("""
                    INSERT INTO knowledge_documents(id,organization_id,knowledge_base_id,
                        name,enabled,created_by_user_id,version)
                    VALUES(?,?,?,?,true,?,0)
                    """, doc, PLATFORM_ORGANIZATION_ID, base, "Test " + doc, admin);
        }

        // Executor remains disabled in the test profile; only the acceptance
        // path is invoked with explicit test-local feature flags.
        var service = new KnowledgeBaseDeletionService(jdbcTemplate, audit, true, true, true, true);
        var actor = SafeAiUserPrincipal.accessTokenPrincipal(admin, PLATFORM_ORGANIZATION_ID,
                0L, 0L, Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        String key = "base-delete-" + UUID.randomUUID();
        var tx = new TransactionTemplate(transactionManager);
        KnowledgeBaseDeletionResponse result =
                tx.execute(status -> service.accept(base, 0, key, actor));
        assertThat(result).isNotNull();
        assertThat(result.operationId()).isNotNull();
        assertThat(result.knowledgeBaseId()).isEqualTo(base);
        assertThat(result.state()).isEqualTo("DELETING");
        assertThat(result.completedAt()).isNull();
        assertThat(result.errorCode()).isNull();

        // Resolve TransactionTemplate.execute() before passing its value to AssertJ.
        // This keeps the idempotency assertion without ambiguous assertThat overloads.
        KnowledgeBaseDeletionResponse replay =
                tx.execute(status -> service.accept(base, 0, key, actor));
        assertThat(replay).isEqualTo(result);

        // A replay must neither create another parent operation nor restore the base.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.knowledge_base_deletions_v82
                WHERE knowledge_base_id=? AND organization_id=?
                """, Integer.class, base, PLATFORM_ORGANIZATION_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.knowledge_bases
                WHERE id=? AND organization_id=? AND lifecycle_state='DELETING'
                  AND NOT enabled AND version=1
                """, Integer.class, base, PLATFORM_ORGANIZATION_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM knowledge_document_deletions_v78
                WHERE knowledge_base_id=? AND state='DELETING'
                """, Integer.class, base)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM knowledge_documents WHERE knowledge_base_id=?
                AND lifecycle_state='DELETING' AND NOT enabled
                """, Integer.class, base)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT document_id)
                FROM public.knowledge_document_deletions_v78
                WHERE knowledge_base_id=? AND organization_id=?
                  AND document_id IN (?, ?)
                """, Integer.class, base, PLATFORM_ORGANIZATION_ID, doc1, doc2))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO knowledge_documents(id,organization_id,knowledge_base_id,
                    name,enabled,created_by_user_id,version)
                VALUES(?,?,?,'Too late',true,?,0)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, base, admin))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
