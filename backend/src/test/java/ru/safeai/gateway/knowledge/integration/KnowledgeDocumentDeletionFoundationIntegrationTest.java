package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentQueryRepository;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeDocumentDeletionFoundationIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private KnowledgeDocumentQueryRepository queries;

    @Test
    void deletingDocumentIsFencedAtDatabaseAndHiddenFromLists() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        insertUser(userId, PLATFORM_ORGANIZATION_ID,
                "delete-" + userId + "@example.test", true,
                "ADMIN", Instant.now());
        jdbcTemplate.update("""
                insert into knowledge_bases (
                  id, organization_id, name, visibility, enabled,
                  created_by_user_id, version
                ) values (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, knowledgeBaseId, PLATFORM_ORGANIZATION_ID,
                "Deletion foundation " + knowledgeBaseId, userId);
        jdbcTemplate.update("""
                insert into knowledge_documents (
                  id, organization_id, knowledge_base_id, name, enabled,
                  created_by_user_id, version
                ) values (?, ?, ?, 'Deleted soon', true, ?, 0)
                """, documentId, PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, userId);

        UUID priorVersionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into knowledge_document_versions (
                  id, organization_id, knowledge_base_id, document_id,
                  version_number, original_filename, media_type, size_bytes,
                  sha256, storage_key, created_by_user_id
                ) values (?, ?, ?, ?, 1, 'existing.txt', 'text/plain', 1,
                          ?, ?, ?)
                """, priorVersionId, PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, documentId, "0".repeat(64),
                "delete-test/" + UUID.randomUUID(), userId);

        assertThat(queries.list(PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, 0, 10, true).content()).hasSize(1);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                update knowledge_documents
                set lifecycle_state='DELETING', enabled=false
                where id=?
                """, documentId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("""
                insert into knowledge_document_deletions_v78 (
                  id, organization_id, knowledge_base_id, document_id,
                  requested_by_user_id, idempotency_key, expected_version
                ) values (?, ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, documentId, userId,
                "delete-" + UUID.randomUUID());
        jdbcTemplate.update("""
                update knowledge_documents
                set lifecycle_state='DELETING', enabled=false
                where id=?
                """, documentId);

        assertThat(queries.list(PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, 0, 10, true).content()).isEmpty();
        assertThat(queries.list(PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, 0, 10, false).content()).isEmpty();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                update knowledge_documents
                set lifecycle_state='AVAILABLE', enabled=true
                where id=?
                """, documentId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into knowledge_document_versions (
                  id, organization_id, knowledge_base_id, document_id,
                  version_number, original_filename, media_type, size_bytes,
                  sha256, storage_key, created_by_user_id
                ) values (?, ?, ?, ?, 2, 'late.txt', 'text/plain', 1,
                          ?, ?, ?)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, documentId, "0".repeat(64),
                "delete-test/" + UUID.randomUUID(), userId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // An ingestion job must not be created for a document being erased.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into knowledge_ingestion_jobs (
                  id, organization_id, knowledge_base_id,
                  document_id, document_version_id, status, attempt, version
                ) values (?, ?, ?, ?, ?, 'PENDING', 0, 0)
                """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId, documentId, priorVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
