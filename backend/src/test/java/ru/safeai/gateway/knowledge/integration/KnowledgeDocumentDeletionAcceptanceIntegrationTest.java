package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentQueryRepository;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentDeletionAcceptanceService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные проверки границы приёма заявки на удаление документа.
 *
 * <p>Проверяются реальные PostgreSQL, Flyway-ограничения и транзакции:
 * идемпотентность, разграничение организаций, версия документа и атомарное
 * переключение состояния в {@code DELETING}. Внешнее хранилище здесь не
 * очищается: это ответственность отдельного исполнителя удаления.</p>
 *
 * <p>Общая тестовая БД защищается блокировкой JUnit и атомарной очисткой
 * в родительском {@link AbstractPostgresIntegrationTest}. Сценарии отказа
 * не отключаются при взаимной блокировке PostgreSQL.</p>
 */
@SpringBootTest(properties = "safeai.rate-limit.ai-messages.enabled=false")
@ActiveProfiles("test")
class KnowledgeDocumentDeletionAcceptanceIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private KnowledgeDocumentDeletionAcceptanceService deletion;

    @Autowired
    private KnowledgeDocumentQueryRepository queries;

    @Test
    void acceptsWithOwnerRightsExactlyOnceAndFencesDocumentAtomically() {
        Fixture fixture = createFixture("Deletion acceptance");
        SafeAiUserPrincipal admin = principal(
                fixture.ownerId(), PLATFORM_ORGANIZATION_ID, "ROLE_ADMIN");
        String idempotencyKey = "safeai-test-delete-" + UUID.randomUUID();

        // Неверная версия должна отклоняться до записи заявки и смены состояния.
        assertThatThrownBy(() -> deletion.accept(
                fixture.baseId(), fixture.documentId(), 77L, idempotencyKey, admin))
                .isInstanceOf(ConflictException.class);
        assertNoDeletionIntent(fixture.documentId());
        assertAvailable(fixture.documentId());

        var accepted = deletion.accept(
                fixture.baseId(), fixture.documentId(), 0L, idempotencyKey, admin);
        assertThat(accepted.operationId()).isNotNull();
        assertThat(accepted.state()).isEqualTo("DELETING");
        assertThat(accepted.completedAt()).isNull();
        assertThat(deletion.status(fixture.baseId(), accepted.operationId(), admin))
                .isEqualTo(accepted);

        // Повтор того же запроса обязан возвращать исходную операцию.
        assertThat(deletion.accept(
                fixture.baseId(), fixture.documentId(), 0L, idempotencyKey, admin))
                .isEqualTo(accepted);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.knowledge_document_deletions_v78
                WHERE organization_id = ? AND document_id = ?
                """, Integer.class, PLATFORM_ORGANIZATION_ID, fixture.documentId()))
                .isEqualTo(1);

        Map<String, Object> state = jdbcTemplate.queryForMap("""
                SELECT lifecycle_state, enabled
                FROM public.knowledge_documents
                WHERE id = ?
                """, fixture.documentId());
        assertThat(state.get("lifecycle_state")).isEqualTo("DELETING");
        assertThat(state.get("enabled")).isEqualTo(false);
        assertThat(queries.list(
                PLATFORM_ORGANIZATION_ID, fixture.baseId(), 0, 10, true).content())
                .isEmpty();

        // Сменить ключ или подменить версию принятой заявки невозможно.
        assertThatThrownBy(() -> deletion.accept(
                fixture.baseId(), fixture.documentId(), 0L,
                "different-key-" + UUID.randomUUID(), admin))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> deletion.accept(
                fixture.baseId(), fixture.documentId(), 1L, idempotencyKey, admin))
                .isInstanceOf(ConflictException.class);
        assertThat(deletion.status(fixture.baseId(), accepted.operationId(), admin))
                .isEqualTo(accepted);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.knowledge_document_deletions_v78
                WHERE document_id = ?
                """, Integer.class, fixture.documentId())).isEqualTo(1);
    }

    @Test
    void implicitViewerCannotAcceptDeletion() {
        Fixture fixture = createFixture("Viewer forbidden");
        UUID viewerId = UUID.randomUUID();
        insertUser(viewerId, PLATFORM_ORGANIZATION_ID,
                "delete-viewer-" + viewerId + "@example.test", true,
                "USER", Instant.now());
        SafeAiUserPrincipal viewer = principal(
                viewerId, PLATFORM_ORGANIZATION_ID, "ROLE_USER");

        // Чтение общей базы не предоставляет права на физическое удаление.
        assertThatThrownBy(() -> deletion.accept(
                fixture.baseId(), fixture.documentId(), 0L,
                "no-owner-" + UUID.randomUUID(), viewer))
                .isInstanceOf(ForbiddenOperationException.class);
        assertNoDeletionIntent(fixture.documentId());
        assertAvailable(fixture.documentId());
    }

    @Test
    void refusesCrossTenantAndDoesNotCreateIntent() {
        Fixture fixture = createFixture("Deletion isolation");
        SafeAiUserPrincipal foreignAdmin = principal(
                UUID.randomUUID(), UUID.randomUUID(), "ROLE_ADMIN");

        // Не раскрываем существование документа в чужой организации.
        assertThatThrownBy(() -> deletion.accept(
                fixture.baseId(), fixture.documentId(), 0L,
                "cross-tenant-" + UUID.randomUUID(), foreignAdmin))
                .isInstanceOf(ResourceNotFoundException.class);
        assertNoDeletionIntent(fixture.documentId());
        assertAvailable(fixture.documentId());
    }

    @Test
    void invalidIdempotencyKeyCannotChangeDocumentOrCreateIntent() {
        Fixture fixture = createFixture("Invalid deletion key");
        SafeAiUserPrincipal admin = principal(
                fixture.ownerId(), PLATFORM_ORGANIZATION_ID, "ROLE_ADMIN");

        // Слишком короткий и отсутствующий ключ отклоняются до изменения БД.
        assertThatThrownBy(() -> deletion.accept(
                fixture.baseId(), fixture.documentId(), 0L, "short", admin))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> deletion.accept(
                fixture.baseId(), fixture.documentId(), 0L, null, admin))
                .isInstanceOf(BadRequestException.class);
        assertNoDeletionIntent(fixture.documentId());
        assertAvailable(fixture.documentId());
    }

    @Test
    void negativeExpectedVersionCannotChangeDocumentOrCreateIntent() {
        Fixture fixture = createFixture("Negative deletion version");
        SafeAiUserPrincipal admin = principal(
                fixture.ownerId(), PLATFORM_ORGANIZATION_ID, "ROLE_ADMIN");

        assertThatThrownBy(() -> deletion.accept(
                fixture.baseId(), fixture.documentId(), -1L,
                "invalid-version-" + UUID.randomUUID(), admin))
                .isInstanceOf(BadRequestException.class);
        assertNoDeletionIntent(fixture.documentId());
        assertAvailable(fixture.documentId());
    }

    /** Создаёт настоящую организационно-ограниченную базу и документ. */
    private Fixture createFixture(String title) {
        Objects.requireNonNull(title, "Название сценария не должно быть null");
        UUID ownerId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        insertUser(ownerId, PLATFORM_ORGANIZATION_ID,
                "delete-owner-" + ownerId + "@example.test", true,
                "ADMIN", Instant.now());

        jdbcTemplate.update("""
                INSERT INTO public.knowledge_bases (
                    id, organization_id, name, visibility, enabled,
                    created_by_user_id, version
                ) VALUES (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """, baseId, PLATFORM_ORGANIZATION_ID, title + " " + baseId, ownerId);
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_documents (
                    id, organization_id, knowledge_base_id, name, enabled,
                    created_by_user_id, version
                ) VALUES (?, ?, ?, ?, true, ?, 0)
                """, documentId, PLATFORM_ORGANIZATION_ID, baseId,
                "Protected document " + documentId, ownerId);
        return new Fixture(ownerId, baseId, documentId);
    }

    /** Проверяет отсутствие даже частично созданного намерения удаления. */
    private void assertNoDeletionIntent(UUID documentId) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.knowledge_document_deletions_v78
                WHERE document_id = ?
                """, Integer.class, documentId)).isZero();
    }

    /** Проверяет, что отклонённое действие не заблокировало исходный документ. */
    private void assertAvailable(UUID documentId) {
        Map<String, Object> document = jdbcTemplate.queryForMap("""
                SELECT lifecycle_state, enabled, version
                FROM public.knowledge_documents
                WHERE id = ?
                """, documentId);
        assertThat(document.get("lifecycle_state")).isEqualTo("AVAILABLE");
        assertThat(document.get("enabled")).isEqualTo(true);
        assertThat(((Number) document.get("version")).longValue()).isZero();
    }

    private static SafeAiUserPrincipal principal(
            UUID actorId, UUID organizationId, String authority) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                actorId, organizationId, 0L, 0L,
                List.of(new SimpleGrantedAuthority(authority)));
    }

    private record Fixture(UUID ownerId, UUID baseId, UUID documentId) {
    }
}
