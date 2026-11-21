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

@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class KnowledgeErasureEvidenceIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test
    void versionEvidenceSurvivesAndIsImmutable() {
        UUID user = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        insertUser(user, PLATFORM_ORGANIZATION_ID,
            "evidence-" + user + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
            INSERT INTO knowledge_bases
              (id, organization_id, name, visibility, enabled, created_by_user_id, version)
            VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
            """, base, PLATFORM_ORGANIZATION_ID, "Evidence " + base, user);
        jdbcTemplate.update("""
            INSERT INTO knowledge_documents
              (id, organization_id, knowledge_base_id, name, enabled, created_by_user_id, version)
            VALUES (?, ?, ?, 'Evidence', true, ?, 0)
            """, doc, PLATFORM_ORGANIZATION_ID, base, user);
        jdbcTemplate.update("""
            INSERT INTO knowledge_document_versions
              (id, organization_id, knowledge_base_id, document_id, version_number,
               original_filename, media_type, size_bytes, sha256, storage_key, created_by_user_id)
            VALUES (?, ?, ?, ?, 1, 'evidence.txt', 'text/plain', 1, ?, ?, ?)
            """, version, PLATFORM_ORGANIZATION_ID, base, doc,
              "0".repeat(64), "evidence-test/" + version, user);
        Integer count = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM knowledge_document_version_identities_v80
            WHERE id=? AND document_id=? AND knowledge_base_id=? AND organization_id=?
            """, Integer.class, version, doc, base, PLATFORM_ORGANIZATION_ID);
        assertThat(count).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "DELETE FROM knowledge_document_version_identities_v80 WHERE id=?", version))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historicalForeignKeysPointToImmutableIdentityNotOriginalContent() {
        String target = jdbcTemplate.queryForObject("""
            SELECT c.confrelid::regclass::text
            FROM pg_constraint c WHERE c.conname='fk_knowledge_retrieval_hits_provenance_v80'
            """, String.class);
        assertThat(target).contains("knowledge_chunk_identities_v80");
        String generationTarget = jdbcTemplate.queryForObject("""
            SELECT c.confrelid::regclass::text
            FROM pg_constraint c WHERE c.conname='fk_knowledge_generation_provenance_v80'
            """, String.class);
        assertThat(generationTarget).contains("knowledge_document_version_identities_v80");
    }
}
