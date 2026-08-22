package ru.safeai.gateway.knowledge.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.embedding.PgVectorSupport;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class KnowledgeRetrievalRepositoryIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private KnowledgeRetrievalRepository repository;

    @Autowired
    private KnowledgeEmbeddingProvider embeddingProvider;

    @Test
    void hybridSearchUsesCurrentReadyVersionAndEnforcesMembershipInSql() {
        UUID memberId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        insertUser(memberId, PLATFORM_ORGANIZATION_ID,
                "retrieval-member@example.test", true, "USER", now);
        insertUser(outsiderId, PLATFORM_ORGANIZATION_ID,
                "retrieval-outsider@example.test", true, "USER", now);
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

        String query = "сколько дней отпуска";
        List<KnowledgeRetrievalHit> memberHits = repository.hybridSearch(
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                memberId,
                false,
                query,
                embeddingProvider.embed(query),
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
                embeddingProvider.embed(query),
                embeddingProvider.model(),
                2,
                20,
                60
        );

        assertThat(memberHits).hasSize(2);
        assertThat(memberHits.getFirst().content())
                .contains("отпусков", "28");
        assertThat(memberHits.getFirst().documentVersionId())
                .isEqualTo(versionId);
        assertThat(outsiderHits).isEmpty();
    }

    private void insertReadyGraph(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            UUID memberId
    ) {
        jdbcTemplate.update("""
                insert into knowledge_bases (
                    id, organization_id, name, visibility, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'MEMBERS', true, ?, 0)
                """,
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                "Retrieval " + knowledgeBaseId,
                memberId
        );
        jdbcTemplate.update("""
                insert into knowledge_base_memberships (
                    id, knowledge_base_id, organization_id, user_id,
                    access_level, version
                ) values (?, ?, ?, ?, 'VIEWER', 0)
                """,
                UUID.randomUUID(),
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                memberId
        );
        jdbcTemplate.update("""
                insert into knowledge_documents (
                    id, organization_id, knowledge_base_id, name, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'hr-policy.txt', true, ?, 0)
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                memberId
        );
        jdbcTemplate.update("""
                insert into knowledge_document_versions (
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
        jdbcTemplate.update(
                "update knowledge_documents set current_version_id = ? "
                        + "where id = ?",
                versionId,
                documentId
        );
        jdbcTemplate.update("""
                insert into knowledge_ingestion_jobs (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, status, attempt, started_at,
                    finished_at, extractor_version, chunker_version,
                    embedding_model, extracted_char_count, chunk_count,
                    version
                ) values (?, ?, ?, ?, ?, 'READY', 1, now(), now(),
                          'test-extractor', 'test-chunker', ?, 100, 2, 0)
                """,
                UUID.randomUUID(),
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId,
                embeddingProvider.model()
        );
    }

    private void insertChunk(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            int ordinal,
            String content
    ) {
        jdbcTemplate.update("""
                insert into knowledge_document_chunks (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, ordinal, content, content_sha256,
                    estimated_tokens, extractor_version, chunker_version,
                    embedding_model, embedding
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test-extractor',
                          'test-chunker', ?, ?::vector)
                """,
                UUID.randomUUID(),
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId,
                ordinal,
                content,
                sha256(content),
                Math.max(1, content.length() / 4),
                embeddingProvider.model(),
                PgVectorSupport.encode(embeddingProvider.embed(content))
        );
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
