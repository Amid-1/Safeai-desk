package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V84 transitions execute ONLY against disposable Testcontainers PostgreSQL. */
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeErasureTerminalAuditIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void documentReviewIsAtomicAndEmittedOnlyOnRealStateChange() {
        UUID id = newDocumentOperation();
        jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET state='NEEDS_REVIEW', attempts=5, last_error_code='IOEXCEPTION'
                WHERE id=?
                """, id);
        assertThat(auditEvents("KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED", id)).isEqualTo(1);
        jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET updated_at=clock_timestamp() WHERE id=?
                """, id);
        assertThat(auditEvents("KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED", id)).isEqualTo(1);
    }

    @Test
    void rolledBackStateChangeRollsBackRequiredAuditIntent() {
        UUID id = newDocumentOperation();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.update("""
                    UPDATE public.knowledge_document_deletions_v78
                    SET state='NEEDS_REVIEW', last_error_code='IOEXCEPTION' WHERE id=?
                    """, id);
            assertThat(auditEvents("KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED", id)).isEqualTo(1);
            throw new IllegalStateException("simulate transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM public.knowledge_document_deletions_v78 WHERE id=?",
                String.class, id)).isEqualTo("DELETING");
        assertThat(auditEvents("KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED", id)).isZero();
    }

    @Test
    void verifiedRetentionBlockIsAuditedAndRemainsImmutable() {
        UUID id = newDocumentOperation();
        jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET state='BLOCKED_BY_RETENTION',last_error_code='OBJECT_LOCK_VERIFIED'
                WHERE id=?
                """, id);
        assertThat(auditEvents("KNOWLEDGE_DOCUMENT_DELETION_RETENTION_BLOCKED", id)).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET state='DELETING' WHERE id=?
                """, id)).isInstanceOf(RuntimeException.class);
        assertThat(auditEvents("KNOWLEDGE_DOCUMENT_DELETION_RETENTION_BLOCKED", id)).isEqualTo(1);
    }

    @Test
    void committedCompletionHasExactlyOneAuditIntentAndCannotBeRewritten() {
        UUID id = newDocumentOperation();
        jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET state='DELETED', completed_at=clock_timestamp(), last_error_code=NULL
                WHERE id=?
                """, id);
        assertThat(auditEvents("KNOWLEDGE_DOCUMENT_DELETION_COMPLETED", id)).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET last_error_code='OVERRIDE' WHERE id=?
                """, id)).isInstanceOf(RuntimeException.class);
        assertThat(auditEvents("KNOWLEDGE_DOCUMENT_DELETION_COMPLETED", id)).isEqualTo(1);
    }

    @Test
    void baseReviewIsAuditedWithoutMutatingDocumentEvidence() {
        UUID organizationId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_base_identities_v82(id,organization_id) VALUES(?,?)
                """, baseId, organizationId);
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_base_deletions_v82
                (id,organization_id,knowledge_base_id,requested_by_user_id,expected_version,idempotency_key)
                VALUES (?,?,?,?,0,?)
                """, operationId, organizationId, baseId, actor, "terminal-audit-" + operationId);
        jdbcTemplate.update("""
                UPDATE public.knowledge_base_deletions_v82
                SET state='NEEDS_REVIEW',last_error_code='CHILD_NEEDS_REVIEW' WHERE id=?
                """, operationId);
        assertThat(auditEvents("KNOWLEDGE_BASE_DELETION_REVIEW_REQUIRED", operationId)).isEqualTo(1);
    }

    private UUID newDocumentOperation() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_document_deletions_v78
                (id,organization_id,knowledge_base_id,document_id,requested_by_user_id,
                 idempotency_key,expected_version)
                VALUES(?,?,?,?,?,?,0)
                """, id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "terminal-audit-" + id);
        return id;
    }

    private long auditEvents(String eventType, UUID operationId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM public.audit_outbox
                        WHERE event_type=? AND details->>'operationId'=?)
                     + (SELECT count(*) FROM public.audit_events
                        WHERE event_type=? AND details->>'operationId'=?)
                """, Long.class, eventType, operationId.toString(),
                eventType, operationId.toString());
        return Objects.requireNonNull(count, "Audit event count query returned null");
    }
}
