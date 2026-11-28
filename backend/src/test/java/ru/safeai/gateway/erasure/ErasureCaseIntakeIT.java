// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ErasureCaseIntakeIT.java
package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL V103 case lifecycle, tenant isolation and immutable history. */
@ActiveProfiles("test")
class ErasureCaseIntakeIT extends AbstractPostgresIntegrationTest {
    @Autowired private ErasureCaseService service;
    @Autowired private ErasureCaseMutations mutations;
    @Autowired private ru.safeai.gateway.user.repository.UserRepository userRepository;
    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID outsiderOrg = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();

    private SafeAiUserPrincipal principal(UUID who, UUID organization) {
        return SafeAiUserPrincipal.accessTokenPrincipal(who, organization, 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private void fixture() {
        insertOrganization(org, "Erasure Test " + org, true);
        insertOrganization(outsiderOrg, "Erasure Foreign " + outsiderOrg, true);
        insertUser(user, org, "erasure-" + user + "@example.test", true,
                "USER", Instant.now().minusSeconds(600));
        insertUser(outsider, outsiderOrg, "erasure-" + outsider + "@example.test", true,
                "USER", Instant.now().minusSeconds(600));
    }

    @Test void ownDraftSubmitWithdrawAndIndependentHistory() {
        fixture();
        var actor = principal(user, org);
        var target = new Target(ResourceType.USER_ACCOUNT, user,
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        var preview = service.preview(target, actor);
        var draft = new DraftCreate(target, preview.previewId(),
                preview.previewDigest(), preview.expectedResourceVersion(), "SELF_REQUEST");
        var created = mutations.create(draft, "erasure-case-123456", actor);
        assertThat(created.state()).isEqualTo("DRAFT");
        assertThat(created.executionStarted()).isFalse();
        assertThat(created.completionProven()).isFalse();
        assertThat(mutations.create(draft, "erasure-case-123456", actor))
                .isEqualTo(created);
        var submitted = mutations.submit(created.requestId(), 0,
                "erasure-submit-123456", actor);
        assertThat(submitted.state()).isEqualTo("SUBMITTED");
        assertThat(mutations.submit(created.requestId(), 0,
                "erasure-submit-123456", actor)).isEqualTo(submitted);
        var withdrawn = mutations.withdraw(created.requestId(), 1,
                "erasure-withdraw-123456", actor);
        assertThat(withdrawn.state()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.executionStarted()).isFalse();
        assertThat(service.mine(actor).items()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.erasure_request_events_v103
                WHERE request_id=?
                """, Integer.class, created.requestId())).isEqualTo(3);
    }

    @Test void expiredPreviewCannotBeSubmittedEvenWithUnchangedResourceVersion() {
        fixture();
        var actor = principal(user, org);
        var target = new Target(ResourceType.USER_ACCOUNT, user,
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        var preview = service.preview(target, actor);
        var created = mutations.create(new DraftCreate(target, preview.previewId(),
                        preview.previewDigest(), preview.expectedResourceVersion(), "SELF_REQUEST"),
                "erasure-expired-draft-1234", actor);
        jdbcTemplate.update("""
                UPDATE public.erasure_previews_v103
                   SET created_at=transaction_timestamp()-interval '2 hours',
                       expires_at=transaction_timestamp()-interval '1 hour'
                 WHERE id=?
                """, preview.previewId());
        assertThatThrownBy(() -> mutations.submit(created.requestId(), 0,
                "erasure-expired-submit-1234", actor))
                .isInstanceOf(ru.safeai.gateway.common.exception.ConflictException.class);
        assertThat(service.get(created.requestId(), actor).state()).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.erasure_request_events_v103
                 WHERE request_id=?
                """, Integer.class, created.requestId())).isEqualTo(1);
    }

    /** Expired or merely created previews must not block the old user-delete service. */
    @Test void standalonePreviewDoesNotBlockDeletingUser() {
        fixture();
        var target = new Target(ResourceType.USER_ACCOUNT, user,
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        var preview = service.preview(target, principal(user, org));
        assertThat(userRepository.hasPermanentDeletionDependencies(user)).isFalse();
        assertThat(jdbcTemplate.update("DELETE FROM public.users WHERE id=?", user)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.erasure_previews_v103 WHERE id=?
                """, Long.class, preview.previewId())).isEqualTo(1);
    }

    /** Withdrawn cases remain auditable without retaining a live actor FK. */
    @Test void withdrawnCaseDoesNotBlockDeletionButPreservesHistory() {
        fixture();
        var actor = principal(user, org);
        var target = new Target(ResourceType.USER_ACCOUNT, user,
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        var preview = service.preview(target, actor);
        var created = mutations.create(new DraftCreate(target, preview.previewId(),
                        preview.previewDigest(), preview.expectedResourceVersion(), "SELF_REQUEST"),
                "erasure-withdraw-delete-12345", actor);
        assertThat(userRepository.hasPermanentDeletionDependencies(user)).isTrue();
        mutations.withdraw(created.requestId(), 0, "erasure-withdraw-123456", actor);
        assertThat(userRepository.hasPermanentDeletionDependencies(user)).isFalse();
        assertThat(jdbcTemplate.update("DELETE FROM public.users WHERE id=?", user)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.erasure_request_events_v103 WHERE request_id=?
                """, Long.class, created.requestId())).isEqualTo(2);
    }

    /** SQL-level safety must also prevent bypassing the service dependency check. */
    @Test void draftOrSubmittedCaseBlocksDirectDatabaseDelete() {
        fixture();
        var actor = principal(user, org);
        var target = new Target(ResourceType.USER_ACCOUNT, user,
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        var preview = service.preview(target, actor);
        var created = mutations.create(new DraftCreate(target, preview.previewId(),
                        preview.previewDigest(), preview.expectedResourceVersion(), "SELF_REQUEST"),
                "erasure-block-delete-12345", actor);
        assertThat(userRepository.hasPermanentDeletionDependencies(user)).isTrue();
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM public.users WHERE id=?", user))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("Active erasure request blocks user deletion");
        mutations.submit(created.requestId(), 0, "erasure-submit-delete-12345", actor);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM public.users WHERE id=?", user))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** Direct tampering must fail in PostgreSQL even with app-level checks bypassed. */
    @Test void databaseRejectsForgedTransitionAndUnrelatedEvent() {
        fixture();
        var actor = principal(user, org);
        var target = new Target(ResourceType.USER_ACCOUNT, user,
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        var preview = service.preview(target, actor);
        var created = mutations.create(new DraftCreate(target, preview.previewId(),
                        preview.previewDigest(), preview.expectedResourceVersion(), "SELF_REQUEST"),
                "erasure-invalid-state-1234", actor);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.erasure_requests_v103 SET version=version+1 WHERE id=?
                """, created.requestId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO public.erasure_request_events_v103
                (id,request_id,organization_id,actor_user_id,event_type,version)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), created.requestId(), org,
                outsider, "WITHDRAWN", 1))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void otherTenantCannotReadOrPreviewForeignAccount() {
        fixture();
        var foreign = principal(outsider, outsiderOrg);
        var target = new Target(ResourceType.USER_ACCOUNT, user,
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        assertThatThrownBy(() -> service.preview(target, foreign)).isInstanceOf(
                ru.safeai.gateway.common.exception.ResourceNotFoundException.class);
        assertThat(service.mine(foreign).items()).isEmpty();
    }

    @Test void dbRuntimeRoleCannotDeleteCaseOrRewriteHistory() {
        fixture();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege('safeai_app',
                'public.erasure_requests_v103','DELETE')
                """, Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege('safeai_app',
                'public.erasure_request_events_v103','TRUNCATE')
                """, Boolean.class)).isFalse();
    }
    @Test void sharedSpringFlywayUsesSeparateNonSuperuserMigrationOwner() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_roles
                WHERE rolname='safeai_migrator' AND rolcanlogin AND NOT rolsuper
                  AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE n.nspname='public' AND p.proname IN ('guard_erasure_hold_v105','guard_erasure_review_v105')
                  AND p.prosecdef AND p.proowner='safeai_migrator'::regrole
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.flyway_schema_history
                WHERE version IS NOT NULL AND success AND installed_by='safeai_migrator'
                """, Long.class)).isEqualTo(108L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.flyway_schema_history WHERE version='0'
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege('safeai_app','public.erasure_review_authorities_v105','UPDATE')
                  OR has_any_column_privilege('safeai_app','public.erasure_review_authorities_v105','UPDATE')
                  OR has_function_privilege('safeai_app','public.guard_erasure_hold_v105()','EXECUTE')
                """, Boolean.class)).isFalse();
    }

    @Test void protectedSuperAdminAccountCannotCreateSelfErasureCase() {
        UUID root = UUID.randomUUID();
        insertUser(root, PLATFORM_ORGANIZATION_ID,
                "root-" + root + "@example.test", true,
                "SUPER_ADMIN", Instant.now().minusSeconds(500));
        var principal = SafeAiUserPrincipal.accessTokenPrincipal(root,
                PLATFORM_ORGANIZATION_ID, 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        var target = new Target(ResourceType.USER_ACCOUNT, root,
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        assertThatThrownBy(() -> service.preview(target, principal))
                .isInstanceOf(ru.safeai.gateway.common.exception.ForbiddenOperationException.class);
    }
}
