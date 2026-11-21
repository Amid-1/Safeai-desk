package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionAcceptanceService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionExecutor;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;
import ru.safeai.gateway.knowledge.testsupport.KnowledgeErasureTestJournal;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.knowledge.config.KnowledgeErasureWorkerDatabaseConfig.WorkerConnections;
import ru.safeai.gateway.knowledge.service.KnowledgeErasureCommitPublisher;
import ru.safeai.gateway.knowledge.storage.ErasureTombstoneJournal;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** V89 Testcontainers: actual PostgreSQL grants, isolated worker JDBC and
 * crash-safe at-least-once external receipt publication. */
@ActiveProfiles("test")
@SpringBootTest
class KnowledgeErasureReceiptV89IT extends AbstractPostgresIntegrationTest {
    @Autowired private KnowledgeDocumentDeletionAcceptanceService acceptance;

    private record CompletedDocument(UUID operationId, UUID org, UUID base,
                                     UUID document, UUID actor) { }

    /** Real source operation and real V89 AFTER UPDATE trigger create the receipt.
     * Never insert synthetic final proof directly or TRUNCATE immutable evidence. */
    private CompletedDocument completedDocument() {
        UUID actor = UUID.randomUUID(), base = UUID.randomUUID(), doc = UUID.randomUUID();
        UUID org = PLATFORM_ORGANIZATION_ID;
        insertUser(actor,org,"receipt-"+actor+"@example.test",true,"ADMIN",Instant.now());
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_bases
                  (id,organization_id,name,visibility,enabled,created_by_user_id,version)
                VALUES (?,?,?,'ORGANIZATION',true,?,0)
                """,base,org,"Receipt "+base,actor);
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_documents
                  (id,organization_id,knowledge_base_id,name,enabled,created_by_user_id,version)
                VALUES (?,?,?,'Receipt test',true,?,0)
                """,doc,org,base,actor);
        SafeAiUserPrincipal principal=SafeAiUserPrincipal.accessTokenPrincipal(
                actor,org,0L,0L,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        UUID operationId=acceptance.accept(base,doc,0L,
                "receipt-"+UUID.randomUUID(),principal).operationId();
        ObjectStorage storage=new ObjectStorage() {
            @Override public void put(String key,InputStream content) { }
            @Override public StoredObject get(String key) { return null; }
            @Override public void delete(String key) { }
            @Override public void purgeAllVersionsVerified(String key) { }
        };
        try (WorkerConnections worker=new WorkerConnections(POSTGRES.getJdbcUrl(),
                "safeai_knowledge_erasure_worker","test-only-knowledge-worker-password")) {
            new KnowledgeDocumentDeletionExecutor(worker.jdbc(),worker.transactionManager(),
                    storage,null,KnowledgeErasureTestJournal.INSTANCE)
                    .processOperation(operationId);
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM public.knowledge_document_deletions_v78 WHERE id=?
                """,String.class,operationId)).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM public.knowledge_erasure_commit_outbox_v89
                WHERE kind='DOCUMENT' AND operation_id=?
                """,String.class,operationId)).isEqualTo("PENDING");
        return new CompletedDocument(operationId,org,base,doc,actor);
    }

    @Test
    void workerCannotForgeAReceiptForAnUnknownErasureOperation() {
        try (WorkerConnections worker = new WorkerConnections(POSTGRES.getJdbcUrl(),
                "safeai_knowledge_erasure_worker", "test-only-knowledge-worker-password")) {
            assertThatThrownBy(() -> worker.jdbc().update("""
                INSERT INTO public.knowledge_erasure_commit_outbox_v89
                 (kind,operation_id,organization_id,knowledge_base_id,document_id,
                  requested_by_user_id,expected_version)
                VALUES('DOCUMENT',?,?,?,?,?,0)
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("receipt must reference a completed erasure operation");
        }
    }

    @Test
    void applicationCannotPhysicallyDeleteKnowledgeAndWorkerIsGranted() {
        assertThat(jdbcTemplate.queryForObject("""
            SELECT has_table_privilege('safeai_app','public.knowledge_documents','DELETE')
            """,Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT has_table_privilege('safeai_app','public.knowledge_bases','DELETE')
            """,Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT has_table_privilege('safeai_knowledge_erasure_worker','public.knowledge_documents','DELETE')
            """,Boolean.class)).isTrue();
        // Direct DELETE must remain denied to the application. V90 exposes
        // a single constrained SECURITY DEFINER path only during COLLECTING.
        assertThat(jdbcTemplate.queryForObject("""
            SELECT has_table_privilege('safeai_app','public.knowledge_document_chunks','DELETE')
            """,Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT has_function_privilege('safeai_app',
              'public.gc_knowledge_generation_chunks_v90(uuid,uuid)', 'EXECUTE')
            """,Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT has_table_privilege('safeai_app','public.knowledge_erasure_commit_outbox_v89','UPDATE')
            """,Boolean.class)).isFalse();
    }

    @Test
    void applicationGucsAloneDoNotAuthenticateDestructiveErasure() {
        assertThat(jdbcTemplate.queryForObject("""
            SELECT public.is_knowledge_erasure_lease_owner_v81(?::uuid)
            """,Boolean.class,UUID.randomUUID())).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT public.is_knowledge_base_erasure_owner_v82(?::uuid)
            """,Boolean.class,UUID.randomUUID())).isFalse();
    }

    @Test
    void onlyTwoIndependentHumanOperatorLoginsMayRequestBlockedRecovery() {
        assertThatThrownBy(() -> jdbcTemplate.queryForObject("""
            SELECT public.review_blocked_knowledge_erasure_v89(
                'DOCUMENT'::varchar,?::uuid,clock_timestamp(),
                'OPS-REVIEW-0001'::varchar,repeat('a',64)::char(64))
            """,String.class,UUID.randomUUID()))
            .hasMessageContaining("human operator role required");
    }

    @Test
    void receiptDeliveredUsingRealWorkerSqlRoleAfterDatabaseCommit() throws Exception {
        CompletedDocument fixture=completedDocument();
        ErasureTombstoneJournal journal=mock(ErasureTombstoneJournal.class);
        try (WorkerConnections worker=new WorkerConnections(
                POSTGRES.getJdbcUrl(),"safeai_knowledge_erasure_worker",
                "test-only-knowledge-worker-password")) {
            new KnowledgeErasureCommitPublisher(worker,journal).pollOperation(fixture.operationId());
        }
        verify(journal).publishDocumentCommitted(fixture.operationId(),fixture.org(),
                fixture.base(),fixture.document());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM public.knowledge_erasure_commit_outbox_v89
                WHERE kind='DOCUMENT' AND operation_id=?
                """,String.class,fixture.operationId())).isEqualTo("DELIVERED");
    }

    @Test
    void journalFailureKeepsReceiptDurableForRetry() throws Exception {
        CompletedDocument fixture=completedDocument();
        ErasureTombstoneJournal journal=mock(ErasureTombstoneJournal.class);
        doThrow(new IOException("simulated unavailable S3")).doNothing().when(journal)
                .publishDocumentCommitted(fixture.operationId(),fixture.org(),
                        fixture.base(),fixture.document());
        try (WorkerConnections worker=new WorkerConnections(
                POSTGRES.getJdbcUrl(),"safeai_knowledge_erasure_worker",
                "test-only-knowledge-worker-password")) {
            KnowledgeErasureCommitPublisher publisher=new KnowledgeErasureCommitPublisher(worker,journal);
            publisher.pollOperation(fixture.operationId());
            assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM public.knowledge_erasure_commit_outbox_v89
                WHERE kind='DOCUMENT' AND operation_id=?
                """,String.class,fixture.operationId())).isEqualTo("PENDING");
            worker.jdbc().update("""
                UPDATE public.knowledge_erasure_commit_outbox_v89
                   SET next_attempt_at='2000-01-01'::timestamptz
                 WHERE kind='DOCUMENT' AND operation_id=?
                """,fixture.operationId());
            publisher.pollOperation(fixture.operationId());
        }
        verify(journal,times(2)).publishDocumentCommitted(fixture.operationId(),
                fixture.org(),fixture.base(),fixture.document());
        assertThat(jdbcTemplate.queryForObject("""
            SELECT state FROM public.knowledge_erasure_commit_outbox_v89
            WHERE kind='DOCUMENT' AND operation_id=?
            """,String.class,fixture.operationId())).isEqualTo("DELIVERED");
    }
}
