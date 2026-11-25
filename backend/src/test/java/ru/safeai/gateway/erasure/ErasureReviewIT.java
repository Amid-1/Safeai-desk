package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;
import ru.safeai.gateway.erasure.ErasureReviewTypes.*;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real PG integration checks for non-destructive, independent R1b review. */
@ActiveProfiles("test")
class ErasureReviewIT extends AbstractPostgresIntegrationTest {
    private static final String TEST_PROOF_KEY = "11".repeat(32);

    @DynamicPropertySource
    static void proofProperties(DynamicPropertyRegistry registry) {
        registry.add("safeai.erasure.review.actor-proof-key", () -> TEST_PROOF_KEY);
    }

    @Autowired ErasureCaseService intake;
    @Autowired ErasureCaseMutations mutations;
    @Autowired ErasureReviewService reviews;

    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();
    private final UUID legal = UUID.randomUUID();
    private final UUID legalOther = UUID.randomUUID();

    private SafeAiUserPrincipal user(UUID id, String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(id,org,0,0,
                List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }

    private void fixture() {
        // Test role is the schema owner. Runtime safeai_app is denied access.
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret)
                VALUES (1,decode(?,'hex'))
                ON CONFLICT(key_id) DO UPDATE SET secret=EXCLUDED.secret
                """, TEST_PROOF_KEY);
        insertOrganization(org,"R1b Review "+org,true);
        for (UUID id : List.of(requester,approver,legal,legalOther)) {
            insertUser(id,org,"r1b-"+id+"@example.test",true,
                    id.equals(requester)?"USER":"ADMIN", Instant.now().minusSeconds(600));
        }
    }

    private UUID submittedRequest() {
        var target = new Target(ResourceType.USER_ACCOUNT,requester,
                Operation.DELETE_ACCOUNT,"PERSONAL_DATA_AND_ACCESS");
        var actor = user(requester,"USER");
        var preview = intake.preview(target,actor);
        var draft = mutations.create(new DraftCreate(target,preview.previewId(),
                preview.previewDigest(),preview.expectedResourceVersion(),"SELF_REQUEST"),
                "r1b-create-"+UUID.randomUUID(),actor);
        mutations.submit(draft.requestId(),0,"r1b-submit-"+UUID.randomUUID(),actor);
        return draft.requestId();
    }

    private void grant(UUID id,String type) {
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_authorities_v105
                (organization_id,user_id,authority,enabled) VALUES (?,?,?,true)
                """,org,id,type);
    }

    @Test
    void noRoleOrGrantGrantsApprovalAndAppCannotMintAuthority() {
        fixture();
        submittedRequest();
        assertThatThrownBy(() -> reviews.queue(user(approver,"ADMIN")))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege('safeai_app',
                 'public.erasure_review_authorities_v105','INSERT')
                """,Boolean.class)).isFalse();
    }

    @Test
    void independentDecisionIsAuditedButNeverExecutes() {
        fixture();
        UUID id = submittedRequest();
        grant(approver,"REVIEW");
        var actor = user(approver,"ADMIN");
        var list = reviews.queue(actor).items();
        assertThat(list).hasSize(1);
        var decided = reviews.decide(id,0,"review-approval-12345",
                new DecisionBody("APPROVED_REVIEW_ONLY","VERIFIED_OWNER_REQUEST"),actor);
        assertThat(decided.reviewState()).isEqualTo("APPROVED_REVIEW_ONLY");
        assertThat(decided.executionAvailable()).isFalse();
        assertThat(decided.completionProven()).isFalse();
        assertThat(reviews.history(id,actor,true).items()).extracting(ReviewEvent::eventCode)
                .containsExactly("SUBMITTED","APPROVED_REVIEW_ONLY");
        assertThatThrownBy(() -> reviews.decide(id,0,"another-approval-12345",
                new DecisionBody("APPROVED_REVIEW_ONLY","VERIFIED_OWNER_REQUEST"),actor))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void holdResetsReviewAndRequiresIndependentRelease() {
        fixture();
        UUID id = submittedRequest();
        grant(approver,"REVIEW");
        grant(legal,"LEGAL");
        grant(legalOther,"LEGAL");
        var reviewer = user(approver,"ADMIN");
        reviews.decide(id,0,"r1b-first-decision-12345",
                new DecisionBody("APPROVED_REVIEW_ONLY","VALID_TENANT_REQUEST"),reviewer);
        var held = reviews.hold(id,1,"r1b-hold-1234567890",
                new HoldBody("LEGAL_REQUEST"),user(legal,"ADMIN"));
        assertThat(held.holdActive()).isTrue();
        assertThat(held.reviewState()).isEqualTo("PENDING");
        assertThatThrownBy(() -> reviews.decide(id,held.version(),"r1b-bypass-12345678",
                new DecisionBody("APPROVED_REVIEW_ONLY","VALID_TENANT_REQUEST"),reviewer))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> reviews.releaseHold(id,held.version(),"r1b-selfrelease-123",user(legal,"ADMIN")))
                .isInstanceOf(ForbiddenOperationException.class);
        var released = reviews.releaseHold(id,held.version(),"r1b-legalrelease-1234",user(legalOther,"ADMIN"));
        assertThat(released.holdActive()).isFalse();
        assertThat(released.executionAvailable()).isFalse();
        assertThat(reviews.history(id,reviewer,true).items()).hasSize(4);
    }

    @Test
    void requesterCannotReviewOwnCaseAndForeignTenantsAreHidden() {
        fixture();
        UUID id = submittedRequest();
        grant(requester,"REVIEW");
        var subject = user(requester,"USER");
        assertThatThrownBy(() -> reviews.decide(id,0,"r1b-self-approve-1234",
                new DecisionBody("APPROVED_REVIEW_ONLY","VERIFIED_OWNER_REQUEST"),subject))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(reviews.readForRequester(id,subject).requestId()).isEqualTo(id);
        UUID other = UUID.randomUUID();
        insertOrganization(other,"Foreign "+other,true);
        UUID otherId = UUID.randomUUID();
        insertUser(otherId,other,"foreign-"+otherId+"@example.test",true,"USER",
                Instant.now().minusSeconds(600));
        var stranger = SafeAiUserPrincipal.accessTokenPrincipal(otherId,other,0,0,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThatThrownBy(() -> reviews.readForRequester(id,stranger))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void withdrawingSubmittedCaseInvalidatesItsReview() {
        fixture();
        UUID id = submittedRequest();
        grant(approver,"REVIEW");
        var subject = user(requester,"USER");
        mutations.withdraw(id,1,"r1b-withdraw-1234567",subject);
        var view = reviews.readForRequester(id,subject);
        assertThat(view.reviewState()).isEqualTo("WITHDRAWN");
        assertThat(reviews.history(id,subject,false).items())
                .extracting(ReviewEvent::eventCode)
                .containsExactly("SUBMITTED","WITHDRAWN");
        assertThatThrownBy(() -> reviews.decide(id,view.version(),"r1b-postwithdraw-123",
                new DecisionBody("APPROVED_REVIEW_ONLY","VALID_TENANT_REQUEST"),
                user(approver,"ADMIN")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void revokedReviewerAndForgedHistoryAreRejectedByDatabase() {
        fixture();
        UUID id = submittedRequest();
        grant(approver,"REVIEW");
        jdbcTemplate.update("""
                UPDATE public.erasure_review_authorities_v105 SET enabled=false
                WHERE organization_id=? AND user_id=? AND authority='REVIEW'
                """,org,approver);
        assertThatThrownBy(() -> reviews.decide(id,0,"r1b-revoked-12345678",
                new DecisionBody("APPROVED_REVIEW_ONLY","VERIFIED_OWNER_REQUEST"),
                user(approver,"ADMIN")))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO public.erasure_review_events_v105
                    (id,request_id,organization_id,actor_user_id,review_version,event_code)
                VALUES (?,?,?,?,?,'APPROVED_REVIEW_ONLY')
                """,UUID.randomUUID(),id,org,approver,999L))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void directSqlCannotSpoofReviewerEvenIfGrantExists() {
        fixture();
        UUID id = submittedRequest();
        grant(approver, "REVIEW");
        // This matches a valid reviewer and state, but lacks the transaction-bound
        // HMAC signed by the Java service. A bare safeai_app SQL UPDATE must fail.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.erasure_reviews_v105
                SET review_state='APPROVED_REVIEW_ONLY',review_version=review_version+1,
                    updated_by_user_id=?,last_event_code='APPROVED_REVIEW_ONLY',
                    reason_code='VERIFIED_OWNER_REQUEST',last_key='no-valid-actor-12345',
                    updated_at=transaction_timestamp()
                WHERE request_id=?
                """,approver,id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(reviews.readForRequester(id,user(requester,"USER")).reviewState())
                .isEqualTo("PENDING");
    }

    @Test
    void cannotInsertLegalHoldAlreadyReleasedOrForgeJournal() {
        fixture();
        UUID id = submittedRequest();
        grant(legal, "LEGAL");
        grant(legalOther, "LEGAL");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO public.erasure_review_holds_v105
                    (id,request_id,organization_id,reason_code,applied_by_user_id,
                     apply_key,released_by_user_id,release_key,released_at)
                VALUES (?,?,?,?,?,?,?,?,transaction_timestamp()+interval '1 second')
                """, UUID.randomUUID(),id,org,"LEGAL_REQUEST",legal,
                "illegal-prereleased-1234",legalOther,"illegal-prereleased-5678"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO public.erasure_review_events_v105
                    (id,request_id,organization_id,actor_user_id,review_version,event_code)
                VALUES (?,?,?,?,999,'APPROVED_REVIEW_ONLY')
                """,UUID.randomUUID(),id,org,legal))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(reviews.history(id,user(requester,"USER"),false).items())
                .extracting(ReviewEvent::eventCode).containsExactly("SUBMITTED");
    }

    @Test
    void holdEventsExactlyMatchAtomicHoldState() {
        fixture();
        UUID id = submittedRequest();
        grant(legal, "LEGAL");
        grant(legalOther, "LEGAL");
        var applied = reviews.hold(id,0,"check-hold-event-12345",
                new HoldBody("MANDATORY_RETENTION"),user(legal,"ADMIN"));
        assertThat(applied.version()).isEqualTo(1);
        assertThat(applied.holdActive()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.erasure_review_holds_v105
                WHERE request_id=? AND released_at IS NULL
                """,Integer.class,id)).isEqualTo(1);
        var released = reviews.releaseHold(id,1,"check-release-event-12345",
                user(legalOther,"ADMIN"));
        assertThat(released.version()).isEqualTo(2);
        assertThat(released.holdActive()).isFalse();
        assertThat(reviews.history(id,user(requester,"USER"),false).items())
                .extracting(ReviewEvent::eventCode)
                .containsExactly("SUBMITTED","HOLD_APPLIED","HOLD_RELEASED");
    }


}
