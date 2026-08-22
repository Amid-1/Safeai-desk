package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
class KnowledgeMigrationIntegrityIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Test
    void ingestionJobMustReferenceTheExactDocumentVersionIdentity() {
        UUID userId = UUID.randomUUID();
        insertUser(
                userId,
                PLATFORM_ORGANIZATION_ID,
                "knowledge-integrity@example.test",
                true,
                "ADMIN",
                Instant.now()
        );

        UUID knowledgeBaseId = UUID.randomUUID();
        UUID firstDocumentId = UUID.randomUUID();
        UUID secondDocumentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        insertKnowledgeBase(knowledgeBaseId, userId);
        insertDocument(firstDocumentId, knowledgeBaseId, userId, "First");
        insertDocument(secondDocumentId, knowledgeBaseId, userId, "Second");
        insertVersion(versionId, firstDocumentId, knowledgeBaseId, userId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into knowledge_ingestion_jobs (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, status, attempt, version
                ) values (?, ?, ?, ?, ?, 'PENDING', 0, 0)
                """,
                UUID.randomUUID(),
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                secondDocumentId,
                versionId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void versionIdentityConstraintIsValidated() {
        Boolean validated = jdbcTemplate.queryForObject("""
                select convalidated
                from pg_constraint
                where conname = 'fk_knowledge_ingestion_jobs_version_identity'
                """, Boolean.class);

        assertThat(validated).isTrue();
    }

    @Test
    void ragProvenanceConstraintsArePresentAndValidated() {
        var constraints = jdbcTemplate.queryForList("""
                select conname
                from pg_constraint
                where conname in (
                    'fk_knowledge_retrieval_runs_chat_turn',
                    'fk_knowledge_answer_passports_turn',
                    'fk_knowledge_answer_passports_retrieval',
                    'fk_knowledge_answer_citations_hit'
                )
                  and convalidated
                order by conname
                """, String.class);

        assertThat(constraints).containsExactlyInAnyOrder(
                "fk_knowledge_retrieval_runs_chat_turn",
                "fk_knowledge_answer_passports_turn",
                "fk_knowledge_answer_passports_retrieval",
                "fk_knowledge_answer_citations_hit"
        );
    }

    @Test
    void reindexUsesNonNullableCopyOnWriteGenerations() {
        Integer nonNullableColumns = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and column_name = 'index_generation'
                  and table_name in (
                      'knowledge_ingestion_jobs',
                      'knowledge_document_chunks'
                  )
                  and is_nullable = 'NO'
                """, Integer.class);

        assertThat(nonNullableColumns).isEqualTo(2);
    }

    private void insertKnowledgeBase(UUID knowledgeBaseId, UUID userId) {
        jdbcTemplate.update("""
                insert into knowledge_bases (
                    id, organization_id, name, visibility, enabled,
                    created_by_user_id, version
                ) values (?, ?, 'Knowledge integrity test', 'ORGANIZATION', true, ?, 0)
                """, knowledgeBaseId, PLATFORM_ORGANIZATION_ID, userId);
    }

    private void insertDocument(
            UUID documentId,
            UUID knowledgeBaseId,
            UUID userId,
            String name
    ) {
        jdbcTemplate.update("""
                insert into knowledge_documents (
                    id, organization_id, knowledge_base_id, name, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, ?, true, ?, 0)
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                name,
                userId);
    }

    private void insertVersion(
            UUID versionId,
            UUID documentId,
            UUID knowledgeBaseId,
            UUID userId
    ) {
        jdbcTemplate.update("""
                insert into knowledge_document_versions (
                    id, organization_id, knowledge_base_id, document_id,
                    version_number, original_filename, media_type, size_bytes,
                    sha256, storage_key, created_by_user_id
                ) values (?, ?, ?, ?, 1, 'source.txt', 'text/plain', 1,
                          ?, ?, ?)
                """,
                versionId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                "0".repeat(64),
                "knowledge-integrity/" + versionId,
                userId);
    }
}
