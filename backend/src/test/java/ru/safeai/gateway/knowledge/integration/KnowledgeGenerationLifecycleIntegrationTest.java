package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunkCandidate;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;
import ru.safeai.gateway.knowledge.extraction.ExtractedSection;
import ru.safeai.gateway.knowledge.ingestion.EmbeddedKnowledgeChunk;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeChunkPersistenceService;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionClaim;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionQueueRepository;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalRepository;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "safeai.knowledge.ingestion.enabled=false",
        "safeai.knowledge.embedding.provider=hashing"
})
class KnowledgeGenerationLifecycleIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired KnowledgeIngestionQueueRepository queue;
    @Autowired KnowledgeChunkPersistenceService persistence;
    @Autowired KnowledgeEmbeddingProvider embedding;
    @Autowired KnowledgeRetrievalRepository retrieval;
    @MockitoBean AuditEventService audit;
    @MockitoBean AuditOutboxScheduler scheduler;

    @Autowired ru.safeai.gateway.knowledge.generation.KnowledgeGenerationGc gc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Autowired ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService retrievalService;

    @Test void servicePersistsEvidenceBeforeReleasingSearchPinAndAuditFailureRollsBack() {
        var f=fixture(); publish(f.claim());
        UUID actor = Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        "select created_by_user_id from knowledge_documents where id=?",
                        UUID.class,
                        f.claim().documentId()),
                "Fixture document must have a creator");
        var user=ru.safeai.gateway.common.security.SafeAiUserPrincipal.accessTokenPrincipal(actor,PLATFORM_ORGANIZATION_ID,0,0,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        var dataSource = Objects.requireNonNull(
                jdbcTemplate.getDataSource(),
                "JdbcTemplate must have a DataSource");
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // A separate connection must not acquire the generation's exclusive GC lock while audit is running.
            try(var connection=dataSource.getConnection(); var statement=connection.prepareStatement(
                "select index_generation from knowledge_index_generations where document_version_id=? for update nowait")) {
                statement.setObject(1,f.claim().documentVersionId());
                assertThatThrownBy(statement::executeQuery).isInstanceOf(java.sql.SQLException.class)
                    .satisfies(error -> assertThat(((java.sql.SQLException)error).getSQLState()).isEqualTo("55P03"));
            }
            return null;
        }).when(audit).record(org.mockito.ArgumentMatchers.eq(user),org.mockito.ArgumentMatchers.eq(PLATFORM_ORGANIZATION_ID),
            org.mockito.ArgumentMatchers.eq(AuditEventType.KNOWLEDGE_RETRIEVAL_COMPLETED),org.mockito.ArgumentMatchers.anyMap());
        var result=retrievalService.retrieve(f.claim().knowledgeBaseId(),new ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalRequest("evidence",1),user);
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_retrieval_hits where retrieval_run_id=?",Integer.class,result.retrievalRunId())).isEqualTo(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit rejected")).when(audit).record(
            org.mockito.ArgumentMatchers.eq(user),org.mockito.ArgumentMatchers.eq(PLATFORM_ORGANIZATION_ID),
            org.mockito.ArgumentMatchers.eq(AuditEventType.KNOWLEDGE_RETRIEVAL_COMPLETED),org.mockito.ArgumentMatchers.anyMap());
        assertThatThrownBy(() -> retrievalService.retrieve(f.claim().knowledgeBaseId(),new ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalRequest("evidence",1),user))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_retrieval_runs",Integer.class)).isEqualTo(1);
    }

    @Test void unreferencedCurrentVersionRetiresAndCannotBeResurrected() {
        var f=fixture(); publish(f.claim());
        jdbcTemplate.update("update knowledge_documents set current_version_id=null where id=?",f.claim().documentId());
        assertThat(gc.collect(Duration.ofDays(1),1,20)).isZero();
        assertThat(state(f.claim(),f.claim().processingToken())).isEqualTo("RETIRED");
        assertThatThrownBy(() -> jdbcTemplate.update("update knowledge_documents set current_version_id=? where id=?",
            f.claim().documentVersionId(),f.claim().documentId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void concurrentCollectorsDeleteEachGenerationOnce() throws Exception {
        var f=fixture(); publish(f.claim()); UUID old=historical(f.claim());
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> work=() -> {
                if (!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("GC test timeout");
                return gc.collect(Duration.ofDays(1),1,20);
            };
            var first=pool.submit(work); var second=pool.submit(work); start.countDown();
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)+second.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(state(f.claim(),old)).isEqualTo("COLLECTED");
    }

    @Test void oldUnreferencedGenerationIsCollectedAndMetadataRemains() {
        var f=fixture(); publish(f.claim());
        UUID old=historical(f.claim());
        assertThat(gc.collect(Duration.ofDays(1),1,20)).isEqualTo(1);
        assertThat(state(f.claim(),old)).isEqualTo("COLLECTED");
        assertThat(countChunks(f.claim().documentVersionId())).isEqualTo(1);
        assertThat(gc.collect(Duration.ofDays(1),1,20)).isZero();
    }

    @Test void retentionAndKeepLatestProtectOldGeneration() {
        var f=fixture(); publish(f.claim()); UUID old=historical(f.claim());
        assertThat(gc.collect(Duration.ofDays(90),1,20)).isZero();
        assertThat(gc.collect(Duration.ofDays(1),2,20)).isZero();
        assertThat(state(f.claim(),old)).isEqualTo("RETIRED");
    }

    @Test void pinMakesConcurrentGcSkipGenerationUntilTransactionEnds() throws Exception {
        var f=fixture(); publish(f.claim()); UUID old=historical(f.claim());
        var pinned=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        try (var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future=pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    jdbcTemplate.queryForList("select index_generation from knowledge_index_generations where document_version_id=? and index_generation=? for share",
                        f.claim().documentVersionId(),old);
                    pinned.countDown();
                    try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("pin test timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }));
            try {
                assertThat(pinned.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(gc.collect(Duration.ofDays(1),1,20)).isZero();
            } finally { release.countDown(); }
            future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(gc.collect(Duration.ofDays(1),1,20)).isEqualTo(1);
    }

    @Test void incompleteGcRollsBackChunkDeletion() {
        var f=fixture(); publish(f.claim()); UUID old=historical(f.claim());
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
            .executeWithoutResult(status -> {
                jdbcTemplate.update("update knowledge_index_generations set state='COLLECTING' where document_version_id=? and index_generation=?",f.claim().documentVersionId(),old);
                jdbcTemplate.update("delete from knowledge_document_chunks where document_version_id=? and index_generation=?",f.claim().documentVersionId(),old);
            })).isInstanceOf(RuntimeException.class);
        assertThat(state(f.claim(),old)).isEqualTo("RETIRED");
        assertThat(countChunks(f.claim().documentVersionId())).isEqualTo(2);
    }

    @Test void immutableMetadataAndActiveChunksCannotBeChangedOrDeleted() {
        var f=fixture(); publish(f.claim());
        assertThatThrownBy(() -> jdbcTemplate.update("update knowledge_index_generations set chunk_count=99 where document_version_id=?",f.claim().documentVersionId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("delete from knowledge_document_chunks where document_version_id=?",f.claim().documentVersionId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("update knowledge_index_generations set state='RETIRED',retired_at=now() where document_version_id=?",f.claim().documentVersionId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void retrievalEvidencePermanentlyProtectsRetiredGeneration() {
        var f=fixture(); publish(f.claim()); UUID old=historical(f.claim());
        UUID run=UUID.randomUUID();
        UUID actor = Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        "select created_by_user_id from knowledge_documents where id=?",
                        UUID.class,
                        f.claim().documentId()),
                "Fixture document must have a creator");
        jdbcTemplate.update("""
            insert into knowledge_retrieval_runs(id,organization_id,knowledge_base_id,user_id,
            query_text,query_sha256,embedding_model,top_k,candidate_limit,rrf_k,started_at,completed_at)
            values(?,?,?,?,?,?,?,?,?,?,now(),now())
            """,run,PLATFORM_ORGANIZATION_ID,f.claim().knowledgeBaseId(),actor,"evidence","a".repeat(64),embedding.model(),1,20,60);
        jdbcTemplate.update("""
            insert into knowledge_retrieval_hits(id,retrieval_run_id,organization_id,knowledge_base_id,
                chunk_id,document_name_snapshot,rank,fused_score,semantic_rank,cosine_similarity)
            select ?,?,?,?,id,'source.txt',1,0.01,1,0.5 from knowledge_document_chunks
            where document_version_id=? and index_generation=?
            """,UUID.randomUUID(),run,PLATFORM_ORGANIZATION_ID,f.claim().knowledgeBaseId(),f.claim().documentVersionId(),old);
        assertThat(gc.collect(Duration.ofDays(1),1,20)).isZero();
        assertThat(state(f.claim(),old)).isEqualTo("RETIRED");
        assertThat(countChunks(f.claim().documentVersionId())).isEqualTo(2);
    }

    @Test void searchPinsActualGenerationAndKeepsTenantIsolation() {
        var f=fixture(); publish(f.claim());
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            assertThat(retrieval.hybridSearch(PLATFORM_ORGANIZATION_ID,f.claim().knowledgeBaseId(),UUID.randomUUID(),true,
                "evidence",embedding.embed("evidence"),embedding.model(),5,20,60)).hasSize(1);
            assertThat(retrieval.hybridSearch(UUID.randomUUID(),f.claim().knowledgeBaseId(),UUID.randomUUID(),true,
                "evidence",embedding.embed("evidence"),embedding.model(),5,20,60)).isEmpty();
        });
    }

    // Historical rows model a migrated generation whose retention period has elapsed.
    private UUID historical(KnowledgeIngestionClaim claim) {
        UUID gen=UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbcTemplate.update("""
                insert into knowledge_document_chunks(id,organization_id,knowledge_base_id,document_id,
                    document_version_id,index_generation,ordinal,content,content_sha256,estimated_tokens,
                    extractor_version,chunker_version,embedding_model,embedding)
                select ?,organization_id,knowledge_base_id,document_id,document_version_id,?,ordinal,
                    content,content_sha256,estimated_tokens,extractor_version,chunker_version,embedding_model,embedding
                from knowledge_document_chunks where document_version_id=? and index_generation=?
                """,UUID.randomUUID(),gen,claim.documentVersionId(),claim.processingToken());
            jdbcTemplate.update("""
                insert into knowledge_index_generations(document_version_id,index_generation,document_id,
                    knowledge_base_id,organization_id,extractor_version,chunker_version,embedding_model,
                    chunk_count,published_at,publication_time_estimated,state,retired_at)
                select document_version_id,?,document_id,knowledge_base_id,organization_id,extractor_version,
                    chunker_version,embedding_model,chunk_count,now()-interval '31 days',true,'RETIRED',now()-interval '30 days'
                from knowledge_index_generations where document_version_id=? and index_generation=?
                """,gen,claim.documentVersionId(),claim.processingToken());
        });
        return gen;
    }
    private String state(KnowledgeIngestionClaim claim,UUID gen) {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        "select state from knowledge_index_generations where document_version_id=? and index_generation=?",
                        String.class, claim.documentVersionId(), gen),
                "Generation must have a state");
    }

    private void publish(KnowledgeIngestionClaim claim) {
        String text = "approved policy evidence";
        var chunk = new KnowledgeChunkCandidate(0, text, "a".repeat(64), 6, 1, 1, "Policy");
        persistence.replaceChunksAndComplete(claim,
                new ExtractedDocument("test-extractor-v1", List.of(
                        new ExtractedSection(1, "Policy", text)), text.length()),
                "test-chunker-v1", List.of(new EmbeddedKnowledgeChunk(chunk, embedding.embed(text))),
                claim.leaseUntil().minusSeconds(30));
    }

    private int countChunks(UUID versionId) {
        Integer result = jdbcTemplate.queryForObject(
                "select count(*) from public.knowledge_document_chunks where document_version_id = ?",
                Integer.class, versionId);
        return Objects.requireNonNull(result, "Chunk count query must return a value");
    }

    private Fixture fixture() {
        var userId = UUID.randomUUID();
        var kb = UUID.randomUUID();
        var doc = UUID.randomUUID();
        var version = UUID.randomUUID();
        var job = UUID.randomUUID();
        insertUser(userId, PLATFORM_ORGANIZATION_ID,
                "chunk-publication-" + userId + "@test.local", true, "ADMIN",
                Instant.now().minus(Duration.ofMinutes(1)));
        jdbcTemplate.update("""
                insert into public.knowledge_bases
                    (id, organization_id, name, visibility, enabled, created_by_user_id, version)
                values (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, kb, PLATFORM_ORGANIZATION_ID, "Publish " + kb, userId);
        jdbcTemplate.update("""
                insert into public.knowledge_documents
                    (id, organization_id, knowledge_base_id, name, enabled, created_by_user_id, version)
                values (?, ?, ?, 'source.txt', true, ?, 0)
                """, doc, PLATFORM_ORGANIZATION_ID, kb, userId);
        jdbcTemplate.update("""
                insert into public.knowledge_document_versions
                    (id, organization_id, knowledge_base_id, document_id, version_number,
                     original_filename, media_type, size_bytes, sha256, storage_key, created_by_user_id)
                values (?, ?, ?, ?, 1, 'source.txt', 'text/plain', 1, ?, ?, ?)
                """, version, PLATFORM_ORGANIZATION_ID, kb, doc,
                "0".repeat(64), "test/" + version, userId);
        jdbcTemplate.update("update public.knowledge_documents set current_version_id = ? where id = ?",
                version, doc);
        jdbcTemplate.update("""
                insert into public.knowledge_ingestion_jobs
                    (id, organization_id, knowledge_base_id, document_id,
                     document_version_id, status, attempt, version)
                values (?, ?, ?, ?, ?, 'PENDING', 0, 0)
                """, job, PLATFORM_ORGANIZATION_ID, kb, doc, version);
        Instant now = Instant.now().plusSeconds(2);
        var claim = queue.claimNext(now, now.plusSeconds(120), 3).orElseThrow();
        assertThat(claim.jobId()).isEqualTo(job);
        queue.transition(claim, KnowledgeIngestionStatus.VALIDATING,
                KnowledgeIngestionStatus.EXTRACTING, now, now.plusSeconds(120));
        queue.transition(claim, KnowledgeIngestionStatus.EXTRACTING,
                KnowledgeIngestionStatus.CHUNKING, now, now.plusSeconds(120));
        return new Fixture(claim);
    }

    private record Fixture(KnowledgeIngestionClaim claim) { }
}
