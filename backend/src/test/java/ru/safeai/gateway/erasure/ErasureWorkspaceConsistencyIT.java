// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ErasureWorkspaceConsistencyIT.java
package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL regression candidate, using the same fixtures as ErasureReviewIT. */
@ActiveProfiles("test")
class ErasureWorkspaceConsistencyIT extends AbstractPostgresIntegrationTest {
    private static final String TEST_PROOF_KEY = "11".repeat(32);

    @DynamicPropertySource
    static void workspaceProperties(DynamicPropertyRegistry registry) {
        registry.add("safeai.erasure.review.actor-proof-key", () -> TEST_PROOF_KEY);
        registry.add("safeai.erasure.requests.enabled", () -> "true");
        registry.add("safeai.erasure.approvals.enabled", () -> "true");
    }

    @Autowired ErasureCaseService intake;
    @Autowired ErasureCaseMutations mutations;
    @Autowired ErasureReviewService reviews;
    @Autowired ErasureWorkspaceReads workspace;

    private SafeAiUserPrincipal actor(UUID org, String role) {
        UUID id = UUID.randomUUID();
        insertUser(id, org, "r23-" + id + "@example.test", true, role, Instant.now().minusSeconds(600));
        return SafeAiUserPrincipal.accessTokenPrincipal(id, org, 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private UUID create(SafeAiUserPrincipal actor, boolean submit) {
        var target = new Target(ResourceType.USER_ACCOUNT, actor.getId(),
                Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        var preview = intake.preview(target, actor);
        var draft = mutations.create(new DraftCreate(target, preview.previewId(),
                preview.previewDigest(), preview.expectedResourceVersion(), "SELF_REQUEST"),
                "r23-create-" + UUID.randomUUID(), actor);
        if (submit) mutations.submit(draft.requestId(), 0, "r23-submit-" + UUID.randomUUID(), actor);
        return draft.requestId();
    }

    private void grant(SafeAiUserPrincipal actor, String type) {
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_authorities_v105
                (organization_id,user_id,authority,enabled) VALUES (?,?,?,true)
                """, actor.getOrganizationId(), actor.getId(), type);
    }

    @Test
    void platformAndTenantProjectionAreIsolatedAndHistoryIsComplete() {
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret)
                VALUES (1,decode(?,'hex')) ON CONFLICT(key_id) DO UPDATE SET secret=EXCLUDED.secret
                """, TEST_PROOF_KEY);
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
        insertOrganization(orgA, "R23 A " + orgA, true);
        insertOrganization(orgB, "R23 B " + orgB, true);
        var platform = actor(PLATFORM_ORGANIZATION_ID, "SUPER_ADMIN");
        var admin = actor(orgA, "ADMIN");
        var legal = actor(orgA, "ADMIN");
        var ordinary = actor(orgA, "USER");
        UUID submitted = create(ordinary, true);
        UUID draft = create(actor(orgA, "USER"), false);
        UUID foreign = create(actor(orgB, "USER"), true);
        grant(legal, "LEGAL");
        reviews.hold(submitted, 0, "r23-hold-" + UUID.randomUUID(),
                new ErasureReviewTypes.HoldBody("LEGAL_REQUEST"), legal);

        var all = workspace.registry(platform, false, "ALL", "", 0);
        var global = workspace.registrySummary(platform);
        assertThat(global.total()).isEqualTo(2);
        assertThat(global.total()).isEqualTo(all.items().size());
        assertThat(global.held()).isEqualTo(1L);
        assertThat(all.items()).extracting(ErasureWorkspaceReads.Item::requestId)
                .containsExactlyInAnyOrder(submitted, foreign);
        assertThat(all.items()).allMatch(item -> item.requester() == null && item.reviewVisible());
        assertThat(workspace.registryDetail(platform, submitted).holdActive()).isTrue();
        assertThat(workspace.registryDetail(platform, submitted).completionProven()).isFalse();
        assertThatThrownBy(() -> workspace.registryDetail(platform, draft))
                .isInstanceOf(ResourceNotFoundException.class);

        var tenant = workspace.registrySummary(admin);
        assertThat(tenant.total()).isEqualTo(2);
        assertThat(tenant.drafts()).isEqualTo(1L);
        assertThat(tenant.pending()).isNull();
        assertThat(workspace.registry(admin, false, "ALL", "", 0).items())
                .extracting(ErasureWorkspaceReads.Item::requestId).containsExactlyInAnyOrder(submitted, draft);
        var detail = workspace.registryDetail(admin, submitted);
        assertThat(detail.reviewVisible()).isFalse();
        assertThat(detail.reviewState()).isNull();
        assertThat(detail.holdActive()).isNull();
        assertThatThrownBy(() -> workspace.registryDetail(admin, foreign))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> workspace.registryDetail(admin, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> workspace.history(admin, "", 0))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> workspace.registrySummary(ordinary))
                .isInstanceOf(ForbiddenOperationException.class);

        grant(admin, "REVIEW");
        assertThat(workspace.registry(admin, true, "ALL", "", 0).items())
                .extracting(ErasureWorkspaceReads.Item::requestId).containsExactly(submitted);
        var history = workspace.history(admin, submitted.toString(), 0).items();
        assertThat(history).extracting(ErasureWorkspaceReads.HistoryItem::eventCode)
                .contains("DRAFT_CREATED", "SUBMITTED", "HOLD_APPLIED");
        assertThat(history).extracting(ErasureWorkspaceReads.HistoryItem::source)
                .contains("INTAKE", "REVIEW");
        assertThat(workspace.history(admin, foreign.toString(), 0).items()).isEmpty();
        assertThat(workspace.history(platform, draft.toString(), 0).items()).isEmpty();
        jdbcTemplate.update("""
                UPDATE public.erasure_review_authorities_v105 SET enabled=false
                WHERE organization_id=? AND user_id=? AND authority='REVIEW'
                """, orgA, admin.getId());
        assertThatThrownBy(() -> workspace.history(admin, "", 0))
                .isInstanceOf(ForbiddenOperationException.class);
    }
}
