package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionAcceptanceService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionExecutor;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the PostgreSQL transactional phase with a fake verified storage provider.
 * Does NOT replace separate real versioned-MinIO/retention integration tests.
 */
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeDocumentDeletionExecutorIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private KnowledgeDocumentDeletionAcceptanceService acceptance;

    @Test
    void purgesOriginalAndOnlineMetadataThenKeepsMinimalDeletionEvidence() {
        UUID actor = UUID.randomUUID(), base = UUID.randomUUID(), doc = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        String key = "deletion-test/" + UUID.randomUUID();
        insertUser(actor, PLATFORM_ORGANIZATION_ID, "executor-" + actor + "@example.test",
                true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
            INSERT INTO knowledge_bases(id, organization_id, name, visibility, enabled,
                                        created_by_user_id, version)
            VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
            """, base, PLATFORM_ORGANIZATION_ID, "Deletion executor " + base, actor);
        jdbcTemplate.update("""
            INSERT INTO knowledge_documents(id, organization_id, knowledge_base_id,
                                            name, enabled, created_by_user_id, version)
            VALUES (?, ?, ?, 'Erase everything online', true, ?, 0)
            """, doc, PLATFORM_ORGANIZATION_ID, base, actor);
        jdbcTemplate.update("""
            INSERT INTO knowledge_document_versions(id, organization_id, knowledge_base_id,
                    document_id, version_number, original_filename, media_type,
                    size_bytes, sha256, storage_key, created_by_user_id)
            VALUES (?, ?, ?, ?, 1, 'original.txt', 'text/plain', 1, ?, ?, ?)
            """, version, PLATFORM_ORGANIZATION_ID, base, doc, "a".repeat(64), key, actor);

        SafeAiUserPrincipal admin = SafeAiUserPrincipal.accessTokenPrincipal(
                actor, PLATFORM_ORGANIZATION_ID, 0L, 0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var operation = acceptance.accept(base, doc, 0, "executor-" + UUID.randomUUID(), admin);
        List<String> purged = new ArrayList<>();
        ObjectStorage store = new ObjectStorage() {
            @Override public void put(String k, InputStream content) { }
            @Override public StoredObject get(String k) { return null; }
            @Override public void delete(String k) { }
            @Override public void purgeAllVersionsVerified(String k) { purged.add(k); }
        };
        new KnowledgeDocumentDeletionExecutor(jdbcTemplate, transactions, store).poll();

        assertThat(purged).containsExactly(key);
        assertThat(acceptance.status(base, operation.operationId(), admin).state())
                .isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_documents WHERE id=?", Integer.class, doc)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_document_versions WHERE document_id=?",
                Integer.class, doc)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_document_version_identities_v80 WHERE document_id=?",
                Integer.class, doc)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_document_erasure_objects_v81 WHERE operation_id=?",
                Integer.class, operation.operationId())).isZero();
    }

    @Test
    void ambiguousUploadIntentIsQuarantinedWithoutPurgingAnyObject() {
        UUID actor = UUID.randomUUID(), base = UUID.randomUUID(), doc = UUID.randomUUID();
        insertUser(actor, PLATFORM_ORGANIZATION_ID, "ambiguous-" + actor + "@example.test",
                true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
            INSERT INTO knowledge_bases(id, organization_id, name, visibility, enabled,
                                        created_by_user_id, version)
            VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
            """, base, PLATFORM_ORGANIZATION_ID, "Uncertain upload " + base, actor);
        jdbcTemplate.update("""
            INSERT INTO knowledge_documents(id, organization_id, knowledge_base_id,
                                            name, enabled, created_by_user_id, version)
            VALUES (?, ?, ?, 'Pending source', true, ?, 0)
            """, doc, PLATFORM_ORGANIZATION_ID, base, actor);
        jdbcTemplate.update("""
            INSERT INTO knowledge_storage_upload_intents (
              id, organization_id, knowledge_base_id, document_id,
              document_version_id, created_by_user_id, storage_key,
              content_sha256, content_size_bytes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
            """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, base, doc,
                UUID.randomUUID(), actor, "uncertain/" + UUID.randomUUID(), "b".repeat(64));
        SafeAiUserPrincipal admin = SafeAiUserPrincipal.accessTokenPrincipal(
                actor, PLATFORM_ORGANIZATION_ID, 0L, 0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var operation = acceptance.accept(base, doc, 0L, "ambiguous-" + UUID.randomUUID(), admin);
        List<String> purged = new ArrayList<>();
        ObjectStorage store = new ObjectStorage() {
            @Override public void put(String k, InputStream content) { }
            @Override public StoredObject get(String k) { return null; }
            @Override public void delete(String k) { }
            @Override public void purgeAllVersionsVerified(String k) { purged.add(k); }
        };
        new KnowledgeDocumentDeletionExecutor(jdbcTemplate, transactions, store).poll();
        assertThat(purged).isEmpty();
        assertThat(acceptance.status(base, operation.operationId(), admin).state())
                .isEqualTo("NEEDS_REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lifecycle_state FROM knowledge_documents WHERE id=?", String.class, doc))
                .isEqualTo("DELETING");
    }
}
