package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunkCandidate;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.embedding.PgVectorSupport;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;
import ru.safeai.gateway.knowledge.extraction.ExtractedSection;
import ru.safeai.gateway.knowledge.generation.KnowledgeGenerationGc;
import ru.safeai.gateway.knowledge.ingestion.EmbeddedKnowledgeChunk;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeChunkPersistenceService;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionClaim;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionQueueRepository;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalRepository;
import ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "safeai.knowledge.ingestion.enabled=false",
        "safeai.knowledge.embedding.provider=hashing"
})
class KnowledgeGenerationLifecycleIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private KnowledgeIngestionQueueRepository queue;

    @Autowired
    private KnowledgeChunkPersistenceService persistence;

    @Autowired
    private KnowledgeEmbeddingProvider embedding;

    @Autowired
    private KnowledgeRetrievalRepository retrieval;

    @MockitoBean
    private AuditEventService audit;

    @MockitoBean
    private AuditOutboxScheduler scheduler;

    @Autowired
    private KnowledgeGenerationGc gc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private KnowledgeRetrievalService retrievalService;

    // Intercept the current typed audit API: the assertions below verify
    // transaction-scoped generation locking and rollback on audit failure.
    @Test
    void servicePersistsEvidenceBeforeReleasingSearchPinAndAuditFailureRollsBack() {
        Fixture fixture = fixture();
        publish(fixture.claim());

        UUID actor = Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        "select created_by_user_id from knowledge_documents where id = ?",
                        UUID.class,
                        fixture.claim().documentId()
                ),
                "Fixture document must have a creator"
        );
        SafeAiUserPrincipal user = SafeAiUserPrincipal.accessTokenPrincipal(
                actor,
                PLATFORM_ORGANIZATION_ID,
                0L,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        var dataSource = Objects.requireNonNull(
                jdbcTemplate.getDataSource(),
                "JdbcTemplate must have a DataSource"
        );

        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();

            // A separate connection must not acquire the generation's exclusive
            // GC lock while immutable retrieval evidence is being persisted.
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("""
                         select index_generation
                         from knowledge_index_generations
                         where document_version_id = ?
                         for update nowait
                         """)) {
                statement.setObject(1, fixture.claim().documentVersionId());
                assertThatThrownBy(statement::executeQuery)
                        .isInstanceOf(java.sql.SQLException.class)
                        .satisfies(error -> assertThat(
                                ((java.sql.SQLException) error).getSQLState()
                        ).isEqualTo("55P03"));
            }
            return null;
        }).when(audit).recordPrincipalSnapshot(
                eq(user),
                eq(PLATFORM_ORGANIZATION_ID),
                eq(AuditEventType.KNOWLEDGE_RETRIEVAL_COMPLETED),
                any(AuditDetails.class)
        );

        var result = retrievalService.retrieve(
                fixture.claim().knowledgeBaseId(),
                new ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalRequest(
                        "evidence",
                        1
                ),
                user
        );

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_retrieval_hits where retrieval_run_id = ?",
                Integer.class,
                result.retrievalRunId()
        )).isEqualTo(1);

        doThrow(new IllegalStateException("audit rejected"))
                .when(audit).recordPrincipalSnapshot(
                        eq(user),
                        eq(PLATFORM_ORGANIZATION_ID),
                        eq(AuditEventType.KNOWLEDGE_RETRIEVAL_COMPLETED),
                        any(AuditDetails.class)
                );

        assertThatThrownBy(() -> retrievalService.retrieve(
                fixture.claim().knowledgeBaseId(),
                new ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalRequest(
                        "evidence",
                        1
                ),
                user
        )).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_retrieval_runs",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void unreferencedSearchableVersionRetiresAndCannotBeResurrected() {
        Fixture fixture = fixture();
        publish(fixture.claim());

        jdbcTemplate.update(
                "update public.knowledge_documents set searchable_version_id = null where id = ?",
                fixture.claim().documentId()
        );

        // The generation is retired immediately but is too young for collection.
        assertThat(gc.collect(Duration.ofDays(1), 1, 20)).isZero();
        assertThat(state(
                fixture.claim(),
                fixture.claim().processingToken()
        )).isEqualTo("RETIRED");

        // V63 permits a searchable pointer only to an exact ACTIVE READY generation.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update public.knowledge_documents set searchable_version_id = ? where id = ?",
                fixture.claim().documentVersionId(),
                fixture.claim().documentId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentCollectorsDeleteEachGenerationOnce() throws Exception {
        Fixture fixture = fixture();
        UUID old = historical(fixture.claim());
        publish(fixture.claim());
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> work = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("GC test timeout");
                }
                return gc.collect(Duration.ofDays(1), 1, 20);
            };

            var first = pool.submit(work);
            var second = pool.submit(work);
            start.countDown();

            assertThat(
                    first.get(10, TimeUnit.SECONDS)
                            + second.get(10, TimeUnit.SECONDS)
            ).isEqualTo(1);
        }

        assertThat(state(fixture.claim(), old)).isEqualTo("COLLECTED");
    }

    @Test
    void oldUnreferencedGenerationIsCollectedAndMetadataRemains() {
        Fixture fixture = fixture();
        UUID old = historical(fixture.claim());
        publish(fixture.claim());

        assertThat(gc.collect(Duration.ofDays(1), 1, 20)).isEqualTo(1);
        assertThat(state(fixture.claim(), old)).isEqualTo("COLLECTED");
        assertThat(countChunks(fixture.claim().documentVersionId())).isEqualTo(1);
        assertThat(gc.collect(Duration.ofDays(1), 1, 20)).isZero();
    }

    @Test
    void retentionAndKeepLatestProtectOldGeneration() {
        Fixture fixture = fixture();
        UUID old = historical(fixture.claim());
        publish(fixture.claim());

        assertThat(gc.collect(Duration.ofDays(90), 1, 20)).isZero();
        assertThat(gc.collect(Duration.ofDays(1), 2, 20)).isZero();
        assertThat(state(fixture.claim(), old)).isEqualTo("RETIRED");
    }

    @Test
    void pinMakesConcurrentGcSkipGenerationUntilTransactionEnds()
            throws Exception {
        Fixture fixture = fixture();
        UUID old = historical(fixture.claim());
        publish(fixture.claim());
        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() ->
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        jdbcTemplate.queryForList(
                                """
                                select index_generation
                                from knowledge_index_generations
                                where document_version_id = ?
                                  and index_generation = ?
                                for share
                                """,
                                fixture.claim().documentVersionId(),
                                old
                        );
                        pinned.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("pin test timeout");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    })
            );

            try {
                assertThat(pinned.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(gc.collect(Duration.ofDays(1), 1, 20)).isZero();
            } finally {
                release.countDown();
            }

            future.get(10, TimeUnit.SECONDS);
        }

        assertThat(gc.collect(Duration.ofDays(1), 1, 20)).isEqualTo(1);
    }

    @Test
    void incompleteGcRollsBackChunkDeletion() {
        Fixture fixture = fixture();
        UUID old = historical(fixture.claim());
        publish(fixture.claim());

        assertThatThrownBy(() ->
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbcTemplate.update(
                            """
                            update public.knowledge_index_generations
                            set state = 'COLLECTING'
                            where document_version_id = ?
                              and index_generation = ?
                            """,
                            fixture.claim().documentVersionId(),
                            old
                    );
                    jdbcTemplate.update(
                            """
                            delete from public.knowledge_document_chunks
                            where document_version_id = ?
                              and index_generation = ?
                            """,
                            fixture.claim().documentVersionId(),
                            old
                    );
                    // Committing COLLECTING without completing the lifecycle is
                    // intentionally rejected by the V63 generation guard.
                })
        ).isInstanceOf(RuntimeException.class);

        assertThat(state(fixture.claim(), old)).isEqualTo("RETIRED");
        assertThat(countChunks(fixture.claim().documentVersionId())).isEqualTo(2);
    }

    @Test
    void immutableMetadataAndActiveChunksCannotBeChangedOrDeleted() {
        Fixture fixture = fixture();
        publish(fixture.claim());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update public.knowledge_index_generations set chunk_count = 99 where document_version_id = ?",
                fixture.claim().documentVersionId()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from public.knowledge_document_chunks where document_version_id = ?",
                fixture.claim().documentVersionId()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                update public.knowledge_index_generations
                set state = 'RETIRED', retired_at = now()
                where document_version_id = ?
                """,
                fixture.claim().documentVersionId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void retrievalEvidencePermanentlyProtectsRetiredGeneration() {
        Fixture fixture = fixture();
        UUID old = historical(fixture.claim());
        publish(fixture.claim());
        UUID run = UUID.randomUUID();

        UUID actor = Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        "select created_by_user_id from knowledge_documents where id = ?",
                        UUID.class,
                        fixture.claim().documentId()
                ),
                "Fixture document must have a creator"
        );

        insertRetrievalRunEvidence(
                run,
                fixture.claim().knowledgeBaseId(),
                actor
        );
        insertRetrievalHitEvidence(
                run,
                fixture.claim(),
                old
        );

        assertThat(gc.collect(Duration.ofDays(1), 1, 20)).isZero();
        assertThat(state(fixture.claim(), old)).isEqualTo("RETIRED");
        assertThat(countChunks(fixture.claim().documentVersionId())).isEqualTo(2);
    }

    @Test
    void searchPinsActualGenerationAndKeepsTenantIsolation() {
        Fixture fixture = fixture();
        publish(fixture.claim());
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        transaction.executeWithoutResult(status -> {
            assertThat(retrieval.hybridSearch(
                    PLATFORM_ORGANIZATION_ID,
                    fixture.claim().knowledgeBaseId(),
                    UUID.randomUUID(),
                    true,
                    "evidence",
                    embedding.embed("evidence"),
                    embedding.model(),
                    5,
                    20,
                    60
            )).hasSize(1);

            assertThat(retrieval.hybridSearch(
                    UUID.randomUUID(),
                    fixture.claim().knowledgeBaseId(),
                    UUID.randomUUID(),
                    true,
                    "evidence",
                    embedding.embed("evidence"),
                    embedding.model(),
                    5,
                    20,
                    60
            )).isEmpty();
        });
    }

    /**
     * Models an old, already-retired generation before the current generation
     * is published. V57/V63 allow at most one ACTIVE generation per document
     * version, so the historical fixture must complete ACTIVE -> RETIRED before
     * {@link #publish(KnowledgeIngestionClaim)} creates the current ACTIVE one.
     */
    private UUID historical(KnowledgeIngestionClaim claim) {
        UUID generation = UUID.randomUUID();
        String text = "approved policy evidence";
        Instant now = Instant.now();
        Instant publishedAt = now.minus(Duration.ofDays(31));
        Instant retiredAt = now.minus(Duration.ofDays(30));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            /*
             * Chunks are inserted before their generation row because the V57
             * chunk seal guard rejects appends once generation metadata exists.
             * The chunk->generation FK is DEFERRABLE INITIALLY DEFERRED, so the
             * transaction remains valid once the generation row is inserted.
             */
            jdbcTemplate.update(
                    """
                    insert into public.knowledge_document_chunks (
                        id, organization_id, knowledge_base_id, document_id,
                        document_version_id, index_generation, ordinal, content,
                        content_sha256, estimated_tokens, page_from, page_to,
                        heading, extractor_version, chunker_version,
                        embedding_model, embedding
                    ) values (
                        ?, ?, ?, ?, ?, ?, 0, ?, ?, 6, 1, 1,
                        'Policy', ?, ?, ?, ?::vector
                    )
                    """,
                    UUID.randomUUID(),
                    claim.organizationId(),
                    claim.knowledgeBaseId(),
                    claim.documentId(),
                    claim.documentVersionId(),
                    generation,
                    text,
                    "a".repeat(64),
                    "test-extractor-v1",
                    "test-chunker-v1",
                    embedding.model(),
                    PgVectorSupport.encode(
                            embedding.embed(text)
                    )
            );

            /*
             * V63 requires every generation to start ACTIVE. There is no current
             * ACTIVE generation yet, so this insert respects the V57 partial
             * unique index.
             */
            jdbcTemplate.update(
                    """
                    insert into public.knowledge_index_generations (
                        document_version_id, index_generation, document_id,
                        knowledge_base_id, organization_id, extractor_version,
                        chunker_version, embedding_model, chunk_count,
                        published_at, publication_time_estimated, state
                    ) values (
                        ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, true, 'ACTIVE'
                    )
                    """,
                    claim.documentVersionId(),
                    generation,
                    claim.documentId(),
                    claim.knowledgeBaseId(),
                    claim.organizationId(),
                    "test-extractor-v1",
                    "test-chunker-v1",
                    embedding.model(),
                    Timestamp.from(publishedAt)
            );

            /*
             * Complete the only legal retirement transition before publishing
             * the current generation. searchable_version_id is still null here,
             * so the historical generation is not protected as searchable.
             */
            int retired = jdbcTemplate.update(
                    """
                    update public.knowledge_index_generations
                    set state = 'RETIRED',
                        retired_at = ?
                    where document_version_id = ?
                      and index_generation = ?
                      and state = 'ACTIVE'
                    """,
                    Timestamp.from(retiredAt),
                    claim.documentVersionId(),
                    generation
            );

            assertThat(retired).isEqualTo(1);
        });

        return generation;
    }


    private void insertRetrievalRunEvidence(
            UUID runId,
            UUID knowledgeBaseId,
            UUID actor
    ) {
        Instant evidenceAt = Instant.now();

        jdbcTemplate.update(
                """
                insert into public.knowledge_retrieval_runs (
                    id, organization_id, knowledge_base_id, user_id,
                    query_text, query_sha256, embedding_model, lexical_profile,
                    top_k, candidate_limit, rrf_k, started_at, completed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                statement -> {
                    statement.setObject(1, runId);
                    statement.setObject(2, PLATFORM_ORGANIZATION_ID);
                    statement.setObject(3, knowledgeBaseId);
                    statement.setObject(4, actor);
                    statement.setString(5, "evidence");
                    statement.setString(6, "a".repeat(64));
                    statement.setString(7, embedding.model());
                    statement.setString(8, "simple+russian-v2");
                    statement.setInt(9, 1);
                    statement.setInt(10, 20);
                    statement.setInt(11, 60);
                    statement.setTimestamp(12, Timestamp.from(evidenceAt));
                    statement.setTimestamp(13, Timestamp.from(evidenceAt));
                }
        );
    }

    private void insertRetrievalHitEvidence(
            UUID runId,
            KnowledgeIngestionClaim claim,
            UUID generation
    ) {
        jdbcTemplate.update(
                """
                insert into public.knowledge_retrieval_hits (
                    id, retrieval_run_id, organization_id, knowledge_base_id,
                    chunk_id, document_name_snapshot, rank, fused_score,
                    semantic_rank, cosine_similarity
                )
                select ?, ?, ?, ?, id, 'source.txt', 1, 0.01, 1, 0.5
                from public.knowledge_document_chunks
                where document_version_id = ?
                  and index_generation = ?
                """,
                statement -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, runId);
                    statement.setObject(3, PLATFORM_ORGANIZATION_ID);
                    statement.setObject(4, claim.knowledgeBaseId());
                    statement.setObject(5, claim.documentVersionId());
                    statement.setObject(6, generation);
                }
        );
    }

    private String state(
            KnowledgeIngestionClaim claim,
            UUID generation
    ) {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        """
                        select state
                        from public.knowledge_index_generations
                        where document_version_id = ?
                          and index_generation = ?
                        """,
                        String.class,
                        claim.documentVersionId(),
                        generation
                ),
                "Generation must have a state"
        );
    }

    private void publish(KnowledgeIngestionClaim claim) {
        String text = "approved policy evidence";
        var chunk = new KnowledgeChunkCandidate(
                0,
                text,
                "a".repeat(64),
                6,
                1,
                1,
                "Policy"
        );

        persistence.replaceChunksAndComplete(
                claim,
                new ExtractedDocument(
                        "test-extractor-v1",
                        List.of(new ExtractedSection(1, "Policy", text)),
                        text.length()
                ),
                "test-chunker-v1",
                List.of(new EmbeddedKnowledgeChunk(
                        chunk,
                        embedding.embed(text)
                )),
                claim.leaseUntil().minusSeconds(30)
        );
    }

    private int countChunks(UUID versionId) {
        Integer result = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.knowledge_document_chunks
                where document_version_id = ?
                """,
                Integer.class,
                versionId
        );
        return Objects.requireNonNull(
                result,
                "Chunk count query must return a value"
        );
    }

    private Fixture fixture() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        insertUser(
                userId,
                PLATFORM_ORGANIZATION_ID,
                "chunk-publication-" + userId + "@test.local",
                true,
                "ADMIN",
                Instant.now().minus(Duration.ofMinutes(1))
        );

        jdbcTemplate.update(
                """
                insert into public.knowledge_bases (
                    id, organization_id, name, visibility, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """,
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                "Publish " + knowledgeBaseId,
                userId
        );

        jdbcTemplate.update(
                """
                insert into public.knowledge_documents (
                    id, organization_id, knowledge_base_id, name,
                    enabled, created_by_user_id, version
                ) values (?, ?, ?, 'source.txt', true, ?, 0)
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                userId
        );

        jdbcTemplate.update(
                """
                insert into public.knowledge_document_versions (
                    id, organization_id, knowledge_base_id, document_id,
                    version_number, original_filename, media_type, size_bytes,
                    sha256, storage_key, created_by_user_id
                ) values (?, ?, ?, ?, 1, 'source.txt', 'text/plain', 1, ?, ?, ?)
                """,
                versionId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                "0".repeat(64),
                "test/" + versionId,
                userId
        );

        // current_version_id remains the latest uploaded version. Publication
        // later advances searchable_version_id atomically through the V63 path.
        jdbcTemplate.update(
                "update public.knowledge_documents set current_version_id = ? where id = ?",
                versionId,
                documentId
        );

        jdbcTemplate.update(
                """
                insert into public.knowledge_ingestion_jobs (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, status, attempt, version
                ) values (?, ?, ?, ?, ?, 'PENDING', 0, 0)
                """,
                jobId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId
        );

        Instant now = Instant.now().plusSeconds(2);
        KnowledgeIngestionClaim claim = queue.claimNext(
                now,
                now.plusSeconds(120),
                3
        ).orElseThrow();

        assertThat(claim.jobId()).isEqualTo(jobId);

        queue.transition(
                claim,
                KnowledgeIngestionStatus.VALIDATING,
                KnowledgeIngestionStatus.EXTRACTING,
                now,
                now.plusSeconds(120)
        );
        queue.transition(
                claim,
                KnowledgeIngestionStatus.EXTRACTING,
                KnowledgeIngestionStatus.CHUNKING,
                now,
                now.plusSeconds(120)
        );

        return new Fixture(claim);
    }

    private record Fixture(KnowledgeIngestionClaim claim) {
        private Fixture {
            Objects.requireNonNull(claim, "claim");
        }
    }
}
