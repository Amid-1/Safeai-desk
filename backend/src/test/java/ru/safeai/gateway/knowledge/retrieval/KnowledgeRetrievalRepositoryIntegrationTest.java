package ru.safeai.gateway.knowledge.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.embedding.PgVectorSupport;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeRetrievalRepositoryIntegrationTest
        extends AbstractPostgresIntegrationTest {

    /**
     * Compatibility generation used by the legacy READY fixture.
     * Keeping it explicit prevents the test from silently depending on defaults.
     */
    private static final UUID LEGACY_INDEX_GENERATION =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Autowired
    private KnowledgeRetrievalRepository repository;

    @Autowired
    private KnowledgeEmbeddingProvider embeddingProvider;

    @Test
    void hybridSearchUsesPublishedSearchableVersionAndEnforcesMembershipInSql() {
        UUID memberId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        insertUser(
                memberId,
                PLATFORM_ORGANIZATION_ID,
                "retrieval-member@example.test",
                true,
                "USER",
                now
        );
        insertUser(
                outsiderId,
                PLATFORM_ORGANIZATION_ID,
                "retrieval-outsider@example.test",
                true,
                "USER",
                now
        );

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    insertReadyGraph(
                            knowledgeBaseId,
                            documentId,
                            versionId,
                            memberId
                    );

                    insertChunk(
                            knowledgeBaseId,
                            documentId,
                            versionId,
                            0,
                            "Политика отпусков разрешает 28 календарных дней."
                    );
                    insertChunk(
                            knowledgeBaseId,
                            documentId,
                            versionId,
                            1,
                            "Инструкция по настройке корпоративной почты."
                    );

                    int generationInserted = jdbcTemplate.update(
                            """
                            insert into public.knowledge_index_generations (
                                document_version_id,
                                index_generation,
                                document_id,
                                knowledge_base_id,
                                organization_id,
                                extractor_version,
                                chunker_version,
                                embedding_model,
                                chunk_count,
                                state
                            )
                            select
                                document_version_id,
                                index_generation,
                                document_id,
                                knowledge_base_id,
                                organization_id,
                                extractor_version,
                                chunker_version,
                                embedding_model,
                                chunk_count,
                                'ACTIVE'
                            from public.knowledge_ingestion_jobs
                            where document_version_id = ?
                              and index_generation = ?
                            """,
                            versionId,
                            LEGACY_INDEX_GENERATION
                    );

                    assertThat(generationInserted)
                            .as("Должна быть создана ровно одна ACTIVE generation")
                            .isEqualTo(1);

                    /*
                     * V63 separates the latest uploaded pointer from the
                     * successfully published retrieval pointer. The old fixture
                     * created READY + ACTIVE evidence but never published
                     * searchable_version_id, so V63-aware retrieval returned 0 hits.
                     */
                    int published = jdbcTemplate.update(
                            """
                            update public.knowledge_documents
                               set searchable_version_id = ?
                             where id = ?
                               and organization_id = ?
                               and knowledge_base_id = ?
                            """,
                            versionId,
                            documentId,
                            PLATFORM_ORGANIZATION_ID,
                            knowledgeBaseId
                    );

                    assertThat(published)
                            .as("Документ должен получить опубликованный searchable_version_id")
                            .isEqualTo(1);
                });

        assertPublishedFixture(
                knowledgeBaseId,
                documentId,
                versionId
        );

        String query = "сколько дней отпуска";
        float[] queryEmbedding = embeddingProvider.embed(query);

        List<KnowledgeRetrievalHit> memberHits = repository.hybridSearch(
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                memberId,
                false,
                query,
                queryEmbedding,
                embeddingProvider.model(),
                2,
                20,
                60
        );

        List<KnowledgeRetrievalHit> outsiderHits = repository.hybridSearch(
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                outsiderId,
                false,
                query,
                queryEmbedding,
                embeddingProvider.model(),
                2,
                20,
                60
        );

        assertThat(memberHits)
                .as("MEMBERS participant должен видеть оба chunk опубликованной generation")
                .hasSize(2);

        assertThat(memberHits.getFirst().content())
                .contains("отпусков", "28");

        assertThat(memberHits)
                .extracting(KnowledgeRetrievalHit::documentVersionId)
                .containsOnly(versionId);

        assertThat(outsiderHits)
                .as("Пользователь без membership должен отфильтровываться внутри retrieval SQL")
                .isEmpty();
    }

    private void insertReadyGraph(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            UUID memberId
    ) {
        jdbcTemplate.update(
                """
                insert into public.knowledge_bases (
                    id, organization_id, name, visibility, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'MEMBERS', true, ?, 0)
                """,
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                "Retrieval " + knowledgeBaseId,
                memberId
        );

        jdbcTemplate.update(
                """
                insert into public.knowledge_base_memberships (
                    id, knowledge_base_id, organization_id, user_id,
                    access_level, version
                ) values (?, ?, ?, ?, 'VIEWER', 0)
                """,
                UUID.randomUUID(),
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                memberId
        );

        jdbcTemplate.update(
                """
                insert into public.knowledge_documents (
                    id, organization_id, knowledge_base_id, name, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'hr-policy.txt', true, ?, 0)
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                memberId
        );

        jdbcTemplate.update(
                """
                insert into public.knowledge_document_versions (
                    id, organization_id, knowledge_base_id, document_id,
                    version_number, original_filename, media_type, size_bytes,
                    sha256, storage_key, created_by_user_id
                ) values (?, ?, ?, ?, 1, 'hr-policy.txt', 'text/plain', 1,
                          ?, ?, ?)
                """,
                versionId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                "0".repeat(64),
                "retrieval-test/" + versionId,
                memberId
        );

        int currentUpdated = jdbcTemplate.update(
                """
                update public.knowledge_documents
                   set current_version_id = ?
                 where id = ?
                   and organization_id = ?
                   and knowledge_base_id = ?
                """,
                versionId,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId
        );

        assertThat(currentUpdated)
                .as("Latest uploaded version должна быть привязана к документу")
                .isEqualTo(1);

        int jobInserted = jdbcTemplate.update(
                """
                insert into public.knowledge_ingestion_jobs (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, status, attempt, started_at,
                    finished_at, extractor_version, chunker_version,
                    embedding_model, extracted_char_count, chunk_count,
                    index_generation, version
                ) values (?, ?, ?, ?, ?, 'READY', 1, now(), now(),
                          'test-extractor', 'test-chunker', ?, 100, 2, ?, 0)
                """,
                UUID.randomUUID(),
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId,
                embeddingProvider.model(),
                LEGACY_INDEX_GENERATION
        );

        assertThat(jobInserted)
                .as("READY ingestion job должен быть создан ровно один раз")
                .isEqualTo(1);
    }

    private void insertChunk(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            int ordinal,
            String content
    ) {
        int inserted = jdbcTemplate.update(
                """
                insert into public.knowledge_document_chunks (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, index_generation, ordinal, content,
                    content_sha256, estimated_tokens, extractor_version,
                    chunker_version, embedding_model, embedding
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'test-extractor',
                          'test-chunker', ?, ?::vector)
                """,
                UUID.randomUUID(),
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId,
                LEGACY_INDEX_GENERATION,
                ordinal,
                content,
                sha256(content),
                Math.max(1, content.length() / 4),
                embeddingProvider.model(),
                PgVectorSupport.encode(embeddingProvider.embed(content))
        );

        assertThat(inserted)
                .as("Chunk ordinal %s должен быть создан", ordinal)
                .isEqualTo(1);
    }

    private void assertPublishedFixture(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId
    ) {
        Map<String, Object> pointers = jdbcTemplate.queryForMap(
                """
                select current_version_id, searchable_version_id
                from public.knowledge_documents
                where id = ?
                  and organization_id = ?
                  and knowledge_base_id = ?
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId
        );

        assertThat(pointers)
                .containsEntry("current_version_id", versionId)
                .containsEntry("searchable_version_id", versionId);

        Integer readyGenerationCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.knowledge_index_generations generation
                join public.knowledge_ingestion_jobs job
                  on job.document_version_id = generation.document_version_id
                 and job.index_generation = generation.index_generation
                where generation.document_version_id = ?
                  and generation.index_generation = ?
                  and generation.organization_id = ?
                  and generation.knowledge_base_id = ?
                  and generation.document_id = ?
                  and generation.embedding_model = ?
                  and generation.state = 'ACTIVE'
                  and job.status = 'READY'
                """,
                Integer.class,
                versionId,
                LEGACY_INDEX_GENERATION,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                embeddingProvider.model()
        );

        assertThat(readyGenerationCount)
                .as("Published version должна иметь exact ACTIVE + READY generation")
                .isEqualTo(1);

        Integer chunkCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.knowledge_document_chunks
                where document_version_id = ?
                  and index_generation = ?
                  and organization_id = ?
                  and knowledge_base_id = ?
                  and document_id = ?
                  and embedding_model = ?
                """,
                Integer.class,
                versionId,
                LEGACY_INDEX_GENERATION,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                embeddingProvider.model()
        );

        assertThat(chunkCount)
                .as("Published generation должна содержать ровно два matching chunk")
                .isEqualTo(2);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    )
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
