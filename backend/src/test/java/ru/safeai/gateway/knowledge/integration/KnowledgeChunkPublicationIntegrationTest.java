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
import ru.safeai.gateway.knowledge.ingestion.StaleIngestionOwnershipException;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalRepository;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "safeai.knowledge.ingestion.enabled=false",
        "safeai.knowledge.embedding.provider=hashing"
})
class KnowledgeChunkPublicationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired KnowledgeIngestionQueueRepository queue;
    @Autowired KnowledgeChunkPersistenceService persistence;
    @Autowired KnowledgeEmbeddingProvider embedding;
    @Autowired KnowledgeRetrievalRepository retrieval;
    @MockitoBean AuditEventService audit;
    @MockitoBean AuditOutboxScheduler scheduler;

    @Test
    void ownedGenerationBecomesReadyAndOnlyItsChunksArePublished() {
        var fixture = fixture();
        publish(fixture.claim());
        assertThat(status(fixture.claim().jobId())).isEqualTo("READY");
        UUID generation = jdbcTemplate.queryForObject(
                "select index_generation from public.knowledge_ingestion_jobs where id = ?",
                UUID.class, fixture.claim().jobId());
        assertThat(generation).isEqualTo(fixture.claim().processingToken());
        Integer chunks = jdbcTemplate.queryForObject(
                "select count(*) from public.knowledge_document_chunks where document_version_id = ? and index_generation = ?",
                Integer.class, fixture.claim().documentVersionId(), generation);
        assertThat(chunks).isEqualTo(1);
    }

    @Test
    void staleTokenCannotWriteAnyOrphanChunksOrChangeStatus() {
        var fixture = fixture();
        var original = fixture.claim();
        var stale = new KnowledgeIngestionClaim(original.jobId(), original.organizationId(),
                original.knowledgeBaseId(), original.documentId(), original.documentVersionId(),
                UUID.randomUUID(), original.attempt(), original.leaseUntil());
        assertThatThrownBy(() -> publish(stale))
                .isInstanceOf(StaleIngestionOwnershipException.class);
        assertThat(status(original.jobId())).isEqualTo("CHUNKING");
        assertThat(countChunks(original.documentVersionId())).isZero();
    }

    @Test
    void failingAuditTransactionCannotLeaveVisibleChunksOrReadyJob() {
        var fixture = fixture();
        doThrow(new IllegalStateException("audit transaction rejected"))
                .when(audit).recordSystem(eq(PLATFORM_ORGANIZATION_ID),
                        eq(AuditEventType.KNOWLEDGE_INGESTION_READY), anyMap());
        assertThatThrownBy(() -> publish(fixture.claim()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit transaction rejected");
        assertThat(status(fixture.claim().jobId())).isEqualTo("CHUNKING");
        assertThat(countChunks(fixture.claim().documentVersionId())).isZero();
    }

    @Test
    void generationAwareUniqueConstraintPreventsDuplicateOrdinalsWithinGeneration() {
        var fixture = fixture();
        publish(fixture.claim());
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into public.knowledge_document_chunks
                    (id, organization_id, knowledge_base_id, document_id,
                     document_version_id, index_generation, ordinal,
                     content, content_sha256, estimated_tokens,
                     extractor_version, chunker_version, embedding_model, embedding)
                select ?, organization_id, knowledge_base_id, document_id,
                       document_version_id, index_generation, ordinal,
                       content, content_sha256, estimated_tokens,
                       extractor_version, chunker_version, embedding_model, embedding
                from public.knowledge_document_chunks
                where document_version_id = ?
                limit 1
                """, UUID.randomUUID(), fixture.claim().documentVersionId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(countChunks(fixture.claim().documentVersionId())).isEqualTo(1);
    }

    @Test
    void generationAwareUniqueConstraintIsInstalledAndValidatedByFlyway() {
        Boolean validated = jdbcTemplate.queryForObject("""
                select convalidated from pg_constraint
                where conrelid = 'public.knowledge_document_chunks'::regclass
                  and conname = 'uq_knowledge_document_chunks_version_generation_ordinal'
                """, Boolean.class);
        assertThat(validated).isTrue();
    }

    @Test
    void copyOnWriteReindexRetainsHistoricalChunksButSearchesOnlyNewGeneration() {
        var fixture = fixture();
        publish(fixture.claim());
        jdbcTemplate.update("""
                update public.knowledge_ingestion_jobs
                set status = 'PENDING', attempt = 0, processing_token = null,
                    claimed_at = null, lease_until = null, next_attempt_at = now(),
                    started_at = null, finished_at = null, version = version + 1
                where id = ?
                """, fixture.claim().jobId());
        Instant claimedAt = Instant.now().plusSeconds(3);
        KnowledgeIngestionClaim second = queue.claimNext(claimedAt,
                claimedAt.plusSeconds(120), 3).orElseThrow();
        queue.transition(second, KnowledgeIngestionStatus.VALIDATING,
                KnowledgeIngestionStatus.EXTRACTING, claimedAt, claimedAt.plusSeconds(120));
        queue.transition(second, KnowledgeIngestionStatus.EXTRACTING,
                KnowledgeIngestionStatus.CHUNKING, claimedAt, claimedAt.plusSeconds(120));
        publish(second, "NEW-GENERATION-ONLY evidence");
        assertThat(countChunks(second.documentVersionId())).isEqualTo(2);
        Integer generations = jdbcTemplate.queryForObject("""
                select count(distinct index_generation)
                from public.knowledge_document_chunks where document_version_id = ?
                """, Integer.class, second.documentVersionId());
        assertThat(generations).isEqualTo(2);
        var hits = retrieval.hybridSearch(PLATFORM_ORGANIZATION_ID,
                second.knowledgeBaseId(), UUID.randomUUID(), true,
                "evidence", embedding.embed("NEW-GENERATION-ONLY evidence"),
                embedding.model(), 5, 20, 60);
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().content()).isEqualTo("NEW-GENERATION-ONLY evidence");
    }

    private void publish(KnowledgeIngestionClaim claim) {
        publish(claim, "approved policy evidence");
    }

    private void publish(KnowledgeIngestionClaim claim, String text) {
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
        return result == null ? -1 : result;
    }

    private String status(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "select status from public.knowledge_ingestion_jobs where id = ?",
                String.class, jobId);
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
