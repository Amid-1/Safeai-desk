// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ChatExecutionAssessmentIT.java
package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;
import ru.safeai.gateway.erasure.ErasureReviewTypes.*;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.erasure.ChatExecutionAssessment.Blocker.*;

/** Actual V86/V102/V103/V105 PostgreSQL. No execution flags enabled. */
@ActiveProfiles("test")
class ChatExecutionAssessmentIT extends AbstractPostgresIntegrationTest {
    private static final String TEST_PROOF = "11".repeat(32);
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("safeai.erasure.review.actor-proof-key", () -> TEST_PROOF);
    }
    @Autowired ErasureCaseService intake;
    @Autowired ErasureCaseMutations mutations;
    @Autowired ErasureReviewService reviews;
    @Autowired ChatExecutionAssessment gate;
    private final UUID org = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private final UUID legal = UUID.randomUUID();
    private final UUID chat = UUID.randomUUID();
    private boolean originalPolicy;
    private boolean restorePolicy;

    @BeforeEach void fixture() {
        originalPolicy = Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT chat_allowed FROM public.platform_erasure_policy_v102 WHERE singleton",Boolean.class));
        restorePolicy = true;
        jdbcTemplate.update("UPDATE public.platform_erasure_policy_v102 SET chat_allowed=true WHERE singleton");
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret)
                VALUES (1,decode(?,'hex')) ON CONFLICT(key_id) DO UPDATE SET secret=EXCLUDED.secret
                """, TEST_PROOF);
        insertOrganization(org, "R3a assessment " + org, true);
        for (UUID id : List.of(owner, reviewer, legal)) {
            insertUser(id, org, "r3a-"+id+"@example.test", true,
                    id.equals(owner) ? "USER" : "ADMIN", Instant.now().minusSeconds(600));
        }
        insertChatSession(chat, owner, org);
        jdbcTemplate.update("""
                INSERT INTO public.chat_messages(id,session_id,organization_id,role,content,client_request_id,
                    created_at,status,usage_status,pricing_status)
                VALUES (?,?,?,'USER','R3A_PRESERVE_TEST',?,clock_timestamp(),'COMPLETED','NOT_APPLICABLE','NOT_APPLICABLE')
                """, UUID.randomUUID(), chat, org, UUID.randomUUID());
        jdbcTemplate.update("""
                UPDATE public.chat_sessions SET archived_at=clock_timestamp(),
                    archived_by_user_id=?,version=version+1 WHERE id=?
                """, owner, chat);
        grant(reviewer, "REVIEW");
        grant(legal, "LEGAL");
    }

    @AfterEach void restorePolicyFixture() {
        if (restorePolicy) jdbcTemplate.update(
                "UPDATE public.platform_erasure_policy_v102 SET chat_allowed=? WHERE singleton",originalPolicy);
    }

    private void grant(UUID id, String authority) {
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_authorities_v105(organization_id,user_id,authority,enabled)
                VALUES (?,?,?,true)
                """, org, id, authority);
    }

    private SafeAiUserPrincipal actor(UUID id, UUID organization, String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(id, organization, 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    private SafeAiUserPrincipal owner() { return actor(owner, org, "USER"); }
    private static String key() { return "r3a-assessment-"+UUID.randomUUID(); }

    private UUID submitted() {
        var target = new Target(ResourceType.CHAT, chat, Operation.REDACT_CONTENT, "FULL_CHAT_CONTENT");
        var preview = intake.preview(target, owner());
        var created = mutations.create(new DraftCreate(target,preview.previewId(),preview.previewDigest(),
                preview.expectedResourceVersion(),"SELF_REQUEST"),key(),owner());
        mutations.submit(created.requestId(),0,key(),owner());
        return created.requestId();
    }
    private void approve(UUID id) {
        reviews.decide(id,0,key(),new DecisionBody("APPROVED_REVIEW_ONLY","VERIFIED_OWNER_REQUEST"),
                actor(reviewer,org,"ADMIN"));
    }
    private void noIntent() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM public.chat_erasure_operations_v86 WHERE chat_id=?",
                Long.class,chat)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT erasure_state FROM public.chat_sessions WHERE id=?",
                String.class,chat)).isEqualTo("AVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM public.chat_sessions WHERE id=?",
                String.class,chat)).isEqualTo("Integration chat");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM public.chat_messages WHERE session_id=? AND content='R3A_PRESERVE_TEST'",
                Long.class,chat)).isEqualTo(1L);
    }

    @Test void pendingAndApprovedAreBothNonExecutableAndReadOnly() {
        UUID id = submitted();
        var pending = gate.assess(id,owner());
        assertThat(pending.blockers()).contains(REVIEW_NOT_APPROVED);
        assertThat(pending.binding()).isEmpty();
        approve(id);
        var approved = gate.assess(id,owner());
        assertThat(approved.blockers()).contains(VERIFIED_IDENTITY_REQUIRED, FROZEN_MANIFEST_REQUIRED,
                INDEPENDENT_EXECUTION_AUTHORITY_REQUIRED, UNIFIED_DB_EXECUTION_GATE_REQUIRED);
        assertThat(approved.binding()).isPresent();
        assertThat(approved.binding().orElseThrow().chatId()).isEqualTo(chat);
        assertThat(approved.permitIssued()).isFalse();
        assertThat(approved.executionAvailable()).isFalse();
        assertThat(gate.assess(id,owner())).isEqualTo(approved);
        noIntent();
    }

    @Test void holdInvalidatesObservationEvenIfAnotherCaseTargetsSameChat() {
        UUID id = submitted();
        approve(id);
        UUID otherCase = submitted();
        reviews.hold(otherCase,0,key(),new HoldBody("LEGAL_REQUEST"),actor(legal,org,"ADMIN"));
        var result = gate.assess(id,owner());
        assertThat(result.blockers()).contains(LEGAL_HOLD_V105);
        assertThat(result.binding()).isEmpty();
        noIntent();
    }

    @Test void grantRevocationAndChangedChatInvalidateObservation() {
        UUID id = submitted();
        approve(id);
        jdbcTemplate.update("""
                UPDATE public.erasure_review_authorities_v105 SET enabled=false
                WHERE organization_id=? AND user_id=? AND authority='REVIEW'
                """,org,reviewer);
        jdbcTemplate.update("UPDATE public.chat_sessions SET version=version+1 WHERE id=?",chat);
        var result = gate.assess(id,owner());
        assertThat(result.blockers()).contains(APPROVER_NOT_CURRENTLY_AUTHORIZED,RESOURCE_CHANGED);
        assertThat(result.binding()).isEmpty();
        noIntent();
    }

    @Test void v86HoldAndPlatformPauseAreIndependent() {
        UUID id = submitted();
        approve(id);
        jdbcTemplate.update("""
                INSERT INTO public.chat_retention_holds_v86(id,chat_id,organization_id,reason_code)
                VALUES (?,?,?,'LEGAL_REQUEST')
                """,UUID.randomUUID(),chat,org);
        jdbcTemplate.update("UPDATE public.platform_erasure_policy_v102 SET chat_allowed=false WHERE singleton");
        var result = gate.assess(id,owner());
        assertThat(result.blockers()).contains(LEGAL_HOLD_V86,POLICY_NOT_ALLOWED);
        assertThat(result.binding()).isEmpty();
        noIntent();
    }

    @Test void withdrawalCannotBecomeExecutionAuthority() {
        UUID id = submitted();
        approve(id);
        mutations.withdraw(id,1,key(),owner());
        var result = gate.assess(id,owner());
        assertThat(result.blockers()).contains(CASE_NOT_SUBMITTED,REVIEW_NOT_APPROVED);
        assertThat(result.binding()).isEmpty();
        noIntent();
    }

    @Test void staleOwnerTokenAndOrganizationVersionAreDenied() {
        UUID id = submitted();
        approve(id);
        var staleUser = SafeAiUserPrincipal.accessTokenPrincipal(owner,org,1,0,owner().getAuthorities());
        var staleOrg = SafeAiUserPrincipal.accessTokenPrincipal(owner,org,0,1,owner().getAuthorities());
        assertThatThrownBy(() -> gate.assess(id,staleUser)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> gate.assess(id,staleOrg)).isInstanceOf(ForbiddenOperationException.class);
        noIntent();
    }

    @Test void sameTenantAdminAndForeignOwnerCannotReadObservation() {
        UUID id = submitted();
        approve(id);
        assertThatThrownBy(() -> gate.assess(id,actor(reviewer,org,"ADMIN")))
                .isInstanceOf(ResourceNotFoundException.class);
        UUID foreignOrg=UUID.randomUUID(), foreign=UUID.randomUUID();
        insertOrganization(foreignOrg,"R3a foreign "+foreignOrg,true);
        insertUser(foreign,foreignOrg,"r3a-"+foreign+"@example.test",true,"USER",Instant.now().minusSeconds(600));
        assertThatThrownBy(() -> gate.assess(id,actor(foreign,foreignOrg,"USER")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> gate.assess(UUID.randomUUID(),owner()))
                .isInstanceOf(ResourceNotFoundException.class);
        noIntent();
    }
}
