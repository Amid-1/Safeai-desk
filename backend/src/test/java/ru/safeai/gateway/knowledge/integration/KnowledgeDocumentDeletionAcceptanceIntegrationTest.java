package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentQueryRepository;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionAcceptanceService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The acceptance boundary is verified against a real PostgreSQL V78/V79 schema. */
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class KnowledgeDocumentDeletionAcceptanceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private KnowledgeDocumentDeletionAcceptanceService deletion;
    @Autowired private KnowledgeDocumentQueryRepository queries;

    @Test
    void acceptsWithOwnerRightsExactlyOnceAndFencesDocumentAtomically() {
        UUID ownerId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        insertUser(ownerId, PLATFORM_ORGANIZATION_ID,
                "delete-accept-" + ownerId + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases (
                  id, organization_id, name, visibility, enabled,
                  created_by_user_id, version
                ) VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, baseId, PLATFORM_ORGANIZATION_ID, "Deletion acceptance " + baseId, ownerId);
        jdbcTemplate.update("""
                INSERT INTO knowledge_documents (
                  id, organization_id, knowledge_base_id, name, enabled,
                  created_by_user_id, version
                ) VALUES (?, ?, ?, ?, true, ?, 0)
                """, documentId, PLATFORM_ORGANIZATION_ID, baseId, "Erase me", ownerId);
        SafeAiUserPrincipal admin = SafeAiUserPrincipal.accessTokenPrincipal(
                ownerId, PLATFORM_ORGANIZATION_ID, 0L, 0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        String key = "safeai-test-delete-" + UUID.randomUUID();

        assertThatThrownBy(() -> deletion.accept(baseId, documentId, 77L, key, admin))
                .isInstanceOf(ConflictException.class);

        var accepted = deletion.accept(baseId, documentId, 0L, key, admin);
        assertThat(accepted.state()).isEqualTo("DELETING");
        assertThat(accepted.completedAt()).isNull();
        assertThat(deletion.status(baseId, accepted.operationId(), admin)).isEqualTo(accepted);
        assertThat(deletion.accept(baseId, documentId, 0L, key, admin)).isEqualTo(accepted);

        var state = jdbcTemplate.queryForMap(
                "SELECT lifecycle_state, enabled FROM knowledge_documents WHERE id=?",
                documentId);
        assertThat(state.get("lifecycle_state")).isEqualTo("DELETING");
        assertThat(state.get("enabled")).isEqualTo(false);
        assertThat(queries.list(PLATFORM_ORGANIZATION_ID, baseId, 0, 10, true)
                .content()).isEmpty();

        Integer operationCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM knowledge_document_deletions_v78
                WHERE organization_id=? AND document_id=?
                """, Integer.class, PLATFORM_ORGANIZATION_ID, documentId);
        assertThat(operationCount).isEqualTo(1);

        assertThatThrownBy(() -> deletion.accept(baseId, documentId, 0L,
                "different-key-" + UUID.randomUUID(), admin))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> deletion.accept(baseId, documentId, 1L, key, admin))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void implicitViewerCannotAcceptDeletion() {
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        insertUser(ownerId, PLATFORM_ORGANIZATION_ID,
                "delete-owner-" + ownerId + "@example.test", true, "ADMIN", Instant.now());
        insertUser(viewerId, PLATFORM_ORGANIZATION_ID,
                "delete-viewer-" + viewerId + "@example.test", true, "USER", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases
                  (id,organization_id,name,visibility,enabled,created_by_user_id,version)
                VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, baseId, PLATFORM_ORGANIZATION_ID, "Viewer forbidden " + baseId, ownerId);
        jdbcTemplate.update("""
                INSERT INTO knowledge_documents
                  (id,organization_id,knowledge_base_id,name,enabled,created_by_user_id,version)
                VALUES (?, ?, ?, ?, true, ?, 0)
                """, documentId, PLATFORM_ORGANIZATION_ID, baseId, "View only", ownerId);
        SafeAiUserPrincipal viewer = SafeAiUserPrincipal.accessTokenPrincipal(
                viewerId, PLATFORM_ORGANIZATION_ID, 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThatThrownBy(() -> deletion.accept(baseId, documentId, 0,
                "no-owner-" + UUID.randomUUID(), viewer))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM knowledge_document_deletions_v78 WHERE document_id=?
                """, Integer.class, documentId)).isZero();
    }

    @Test
    void refusesCrossTenantAndDoesNotCreateIntent() {
        UUID actorId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        insertUser(actorId, PLATFORM_ORGANIZATION_ID,
                "delete-tenant-" + actorId + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases (id,organization_id,name,visibility,enabled,created_by_user_id,version)
                VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, baseId, PLATFORM_ORGANIZATION_ID, "Deletion isolation " + baseId, actorId);
        jdbcTemplate.update("""
                INSERT INTO knowledge_documents
                 (id,organization_id,knowledge_base_id,name,enabled,created_by_user_id,version)
                VALUES (?, ?, ?, ?, true, ?, 0)
                """, documentId, PLATFORM_ORGANIZATION_ID, baseId, "Protected", actorId);
        SafeAiUserPrincipal wrongOrg = SafeAiUserPrincipal.accessTokenPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThatThrownBy(() -> deletion.accept(baseId, documentId, 0L,
                "cross-tenant-" + UUID.randomUUID(), wrongOrg))
                .isInstanceOf(ResourceNotFoundException.class);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM knowledge_document_deletions_v78 WHERE document_id = ?
                """, Integer.class, documentId);
        assertThat(count).isZero();
    }
}
