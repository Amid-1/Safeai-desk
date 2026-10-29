package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.controller.KnowledgeDocumentDeletionController;
import ru.safeai.gateway.knowledge.controller.KnowledgeDocumentDeletionReadController;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseDeletionService;
import ru.safeai.gateway.knowledge.service.KnowledgeDeletionOperatorService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Only the disposable Testcontainers PostgreSQL is changed by these tests. */
@SpringBootTest(properties = {"safeai.rate-limit.ai-messages.enabled=false"})
@ActiveProfiles("test")
class KnowledgeDeletionOperatorSafetyIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private AuditEventService audit;
    @Autowired private KnowledgeDeletionOperatorService disabledOperator;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping routes;

    @Test
    void readEndpointsRemainMappedWithAllDestructiveFlagsOff() {
        assertThat(routes.getHandlerMethods().entrySet().stream().anyMatch(entry ->
                entry.getValue().getBeanType().equals(KnowledgeDocumentDeletionReadController.class)
                        && entry.getKey().getMethodsCondition().getMethods().contains(RequestMethod.GET)))
                .isTrue();
        assertThat(routes.getHandlerMethods().entrySet().stream().noneMatch(entry ->
                entry.getValue().getBeanType().equals(KnowledgeDocumentDeletionController.class)))
                .isTrue();
    }

    @Test
    void operatorRecoveryIsOffByDefaultEvenForCurrentAdministrator() {
        Fixture fixture = fixture();
        assertThat(disabledOperator.documentDiagnostics(fixture.base(), fixture.operation(), fixture.admin())
                .recoveryConfigured()).isFalse();
        assertThatThrownBy(() -> disabledOperator.resumeDocument(fixture.base(), fixture.operation(),
                "2026-10-29T00:00:00Z", "OPERATOR_REVIEWED", fixture.admin()))
                .isInstanceOf(ConflictException.class);
        assertThat(state(fixture.operation())).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void auditedResumeOfNeedsReviewUsesFreshSnapshotAndPreservesOriginalIdentity() {
        Fixture fixture = fixture();
        var operator = enabledOperator();
        var before = operator.documentDiagnostics(fixture.base(), fixture.operation(), fixture.admin());
        assertThat(before.state()).isEqualTo("NEEDS_REVIEW");
        assertThat(before.lastErrorCode()).isEqualTo("IOEXCEPTION");
        assertThat(before.attempts()).isEqualTo(5);

        var result = new TransactionTemplate(transactionManager).execute(tx -> operator.resumeDocument(
                fixture.base(), fixture.operation(), before.updatedAt().toString(),
                "OPERATOR_REVIEWED", fixture.admin()));
        assertThat(result).isNotNull();
        assertThat(result.state()).isEqualTo("DELETING");
        assertThat(result.attempts()).isEqualTo(5);
        assertThat(state(fixture.operation())).isEqualTo("DELETING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.audit_outbox
                WHERE event_type='KNOWLEDGE_DOCUMENT_DELETION_RESUMED'
                """, Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx ->
                operator.resumeDocument(fixture.base(), fixture.operation(),
                        before.updatedAt().toString(), "OPERATOR_REVIEWED", fixture.admin())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void immutableRetentionBlockCannotBeResumed() {
        Fixture fixture = fixture();
        jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET state='BLOCKED_BY_RETENTION',last_error_code='OBJECT_LOCK_VERIFIED'
                WHERE id=?
                """, fixture.operation());
        var operator = enabledOperator();
        var diagnostics = operator.documentDiagnostics(fixture.base(), fixture.operation(), fixture.admin());
        assertThat(diagnostics.state()).isEqualTo("BLOCKED_BY_RETENTION");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx ->
                operator.resumeDocument(fixture.base(), fixture.operation(),
                        diagnostics.updatedAt().toString(), "OPERATOR_REVIEWED", fixture.admin())))
                .isInstanceOf(ConflictException.class);
        assertThat(state(fixture.operation())).isEqualTo("BLOCKED_BY_RETENTION");
    }

    @Test
    void revokedDatabaseRoleCannotResumeEvenWithStaleAdminPrincipal() {
        Fixture fixture = fixture();
        // V20 and V24 require exactly one role per user. Downgrade the role
        // in PostgreSQL while retaining the stale ROLE_ADMIN token principal.
        int changedRoles = jdbcTemplate.update("""
                UPDATE public.user_roles
                SET role_id=?
                WHERE user_id=? AND role_id=?
                """, roleId("USER"), fixture.actor(), roleId("ADMIN"));
        assertThat(changedRoles).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT r.name FROM public.user_roles ur
                JOIN public.roles r ON r.id=ur.role_id
                WHERE ur.user_id=?
                """, String.class, fixture.actor())).isEqualTo("USER");
        var operator = enabledOperator();
        assertThatThrownBy(() -> operator.documentDiagnostics(fixture.base(),
                fixture.operation(), fixture.admin())).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx ->
                operator.resumeDocument(fixture.base(), fixture.operation(),
                        Instant.now().toString(), "OPERATOR_REVIEWED", fixture.admin())))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(state(fixture.operation())).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void uncertainUploadIntentForbidsManualResume() {
        Fixture fixture = fixture(true);
        var operator = enabledOperator();
        var snapshot = operator.documentDiagnostics(fixture.base(), fixture.operation(), fixture.admin());
        assertThat(snapshot.unsettledUploadIntents()).isEqualTo(1);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx ->
                operator.resumeDocument(fixture.base(), fixture.operation(),
                        snapshot.updatedAt().toString(), "OPERATOR_REVIEWED", fixture.admin())))
                .isInstanceOf(ConflictException.class);
        assertThat(state(fixture.operation())).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void baseNeedsReviewContinuationIsIndependentlyAudited() {
        UUID actor = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        insertUser(actor, PLATFORM_ORGANIZATION_ID, "resume-base-" + actor + "@example.test",
                true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_bases(id,organization_id,name,visibility,
                    enabled,created_by_user_id,version)
                VALUES (?,?,?,'ORGANIZATION',true,?,0)
                """, base, PLATFORM_ORGANIZATION_ID, "Operator " + base, actor);
        var principal = admin(actor);
        KnowledgeBaseDeletionService acceptor = new KnowledgeBaseDeletionService(
                jdbcTemplate, audit, true, true, true, true);
        var accepted = new TransactionTemplate(transactionManager).execute(tx ->
                acceptor.accept(base, 0L, "operator-test-" + UUID.randomUUID(), principal));
        assertThat(accepted).isNotNull();
        jdbcTemplate.update("""
                UPDATE public.knowledge_base_deletions_v82
                SET state='NEEDS_REVIEW',last_error_code='TEST_REVIEW'
                WHERE id=?
                """, accepted.operationId());
        var operator = enabledOperator();
        var before = operator.baseDiagnostics(base, accepted.operationId(), principal);
        var resumed = new TransactionTemplate(transactionManager).execute(tx -> operator.resumeBase(
                base, accepted.operationId(), before.updatedAt().toString(),
                "OPERATOR_REVIEWED", principal));
        assertThat(resumed).isNotNull();
        assertThat(resumed.state()).isEqualTo("DELETING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.audit_outbox
                WHERE event_type='KNOWLEDGE_BASE_DELETION_RESUMED'
                """, Integer.class)).isEqualTo(1);
    }

    private Fixture fixture() {
        return fixture(false);
    }

    private Fixture fixture(boolean ambiguousUpload) {
        UUID actor = UUID.randomUUID();
        UUID base = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        insertUser(actor, PLATFORM_ORGANIZATION_ID, "resume-doc-" + actor + "@example.test",
                true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_bases(id,organization_id,name,visibility,
                    enabled,created_by_user_id,version)
                VALUES (?,?,?,'ORGANIZATION',true,?,0)
                """, base, PLATFORM_ORGANIZATION_ID, "Resume " + base, actor);
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_documents(id,organization_id,knowledge_base_id,
                    name,enabled,created_by_user_id,version)
                VALUES (?,?,?,?,true,?,0)
                """, document, PLATFORM_ORGANIZATION_ID, base, "Resume " + document, actor);
        if (ambiguousUpload) {
            jdbcTemplate.update("""
                    INSERT INTO public.knowledge_storage_upload_intents(
                        id,organization_id,knowledge_base_id,document_id,
                        document_version_id,created_by_user_id,storage_key,
                        content_sha256,content_size_bytes)
                    VALUES(?,?,?,?,?,?,?,?,1)
                    """, UUID.randomUUID(), PLATFORM_ORGANIZATION_ID, base, document,
                    UUID.randomUUID(), actor, "operator-uncertain/" + UUID.randomUUID(),
                    "a".repeat(64));
        }
        jdbcTemplate.update("""
                INSERT INTO public.knowledge_document_deletions_v78(id,organization_id,
                    knowledge_base_id,document_id,requested_by_user_id,
                    idempotency_key,expected_version)
                VALUES (?,?,?,?,?,?,0)
                """, operation, PLATFORM_ORGANIZATION_ID, base, document,
                actor, "operator-test-" + UUID.randomUUID());
        jdbcTemplate.update("""
                UPDATE public.knowledge_documents
                SET enabled=false,lifecycle_state='DELETING',version=version+1
                WHERE id=?
                """, document);
        jdbcTemplate.update("""
                UPDATE public.knowledge_document_deletions_v78
                SET state='NEEDS_REVIEW',attempts=5,last_error_code='IOEXCEPTION'
                WHERE id=?
                """, operation);
        return new Fixture(actor, base, operation, admin(actor));
    }

    private KnowledgeDeletionOperatorService enabledOperator() {
        return new KnowledgeDeletionOperatorService(jdbcTemplate, audit,
                true, true, true, true, true);
    }

    private String state(UUID operation) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM public.knowledge_document_deletions_v78 WHERE id=?",
                String.class, operation);
    }

    private static SafeAiUserPrincipal admin(UUID id) {
        return SafeAiUserPrincipal.accessTokenPrincipal(id, PLATFORM_ORGANIZATION_ID,
                0L, 0L, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private record Fixture(UUID actor, UUID base, UUID operation, SafeAiUserPrincipal admin) { }
}
