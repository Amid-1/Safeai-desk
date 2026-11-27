package ru.safeai.gateway.erasure;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.safeai.gateway.auth.service.JwtService;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real migrated PostgreSQL, real Spring MVC JSON and the application's JWT security chain. */
@ActiveProfiles("test")
class ErasureRegistryHttpIT extends AbstractPostgresIntegrationTest {
    private static final String PROOF_KEY = "11".repeat(32);
    private static final List<String> STATES = List.of("ALL", "ATTENTION", "HELD", "PENDING",
            "NEEDS_INFORMATION", "APPROVED_REVIEW_ONLY", "REJECTED", "WITHDRAWN", "SUBMITTED", "DRAFT");

    @DynamicPropertySource
    static void registryProperties(DynamicPropertyRegistry registry) {
        registry.add("safeai.erasure.review.actor-proof-key", () -> PROOF_KEY);
        registry.add("safeai.erasure.requests.enabled", () -> "true");
        registry.add("safeai.erasure.approvals.enabled", () -> "true");
    }

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtService jwt;
    @Autowired ErasureCaseService intake;
    @Autowired ErasureCaseMutations mutations;
    @Autowired ErasureReviewService reviews;
    private MockMvc mvc;

    @BeforeEach
    void prepareMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret)
                VALUES (1,decode(?,'hex')) ON CONFLICT(key_id) DO UPDATE SET secret=EXCLUDED.secret
                """, PROOF_KEY);
    }

    private UUID organization() {
        UUID id = UUID.randomUUID();
        insertOrganization(id, "R254 " + id, true);
        return id;
    }

    private record Actor(SafeAiUserPrincipal principal, String token) { }

    private Actor actor(UUID organization, String role) {
        UUID id = UUID.randomUUID();
        insertUser(id, organization, "r254-" + id + "@example.test", true, role, Instant.now().minusSeconds(600));
        var principal = SafeAiUserPrincipal.accessTokenPrincipal(id, organization, 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return new Actor(principal, jwt.generateToken(new AccessTokenSubject(id, organization, 0, 0, Set.of(role))));
    }

    private UUID create(Actor actor, boolean submit) {
        var principal = actor.principal();
        var target = new Target(ResourceType.USER_ACCOUNT, principal.getId(), Operation.DELETE_ACCOUNT, "PERSONAL_DATA_AND_ACCESS");
        var preview = intake.preview(target, principal);
        var draft = mutations.create(new DraftCreate(target, preview.previewId(), preview.previewDigest(),
                preview.expectedResourceVersion(), "SELF_REQUEST"), "r254-create-" + UUID.randomUUID(), principal);
        if (submit) mutations.submit(draft.requestId(), 0, "r254-submit-" + UUID.randomUUID(), principal);
        return draft.requestId();
    }

    private void grant(Actor actor, String type) {
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_authorities_v105(organization_id,user_id,authority,enabled)
                VALUES (?,?,?,true)
                """, actor.principal().getOrganizationId(), actor.principal().getId(), type);
    }

    private ResultActions request(Actor actor, String endpoint, String state, String query, int page) throws Exception {
        return mvc.perform(get("/api/admin/erasure/" + endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token())
                .param("state", state).param("query", query).param("page", Integer.toString(page)));
    }

    private void assertPage(ResultActions response, int page, UUID... ids) throws Exception {
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(page))
                .andExpect(jsonPath("$.pageSize").value(25))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(ids.length));
        if (ids.length == 0) {
            response.andExpect(jsonPath("$.items").isEmpty());
        } else {
            response.andExpect(jsonPath("$.items[*].requestId", containsInAnyOrder(
                    java.util.Arrays.stream(ids).map(UUID::toString).toArray(String[]::new))));
        }
    }

    @Test
    void everyEmptyFilterReturnsAnExplicitArrayAndUnauthorizedRequestsStayDenied() throws Exception {
        var platform = actor(PLATFORM_ORGANIZATION_ID, "SUPER_ADMIN");
        UUID org = organization();
        var admin = actor(org, "ADMIN");
        var user = actor(org, "USER");
        for (String state : STATES) {
            assertPage(request(platform, "registry", state, "", 0), 0);
            assertPage(request(admin, "registry", state, "", 0), 0);
        }
        assertPage(request(platform, "registry", "ALL", "no-match", 3), 3);
        request(user, "registry", "ALL", "", 0).andExpect(status().isForbidden());
        request(admin, "review-queue", "ALL", "", 0).andExpect(status().isForbidden());
        request(platform, "review-queue", "ALL", "", 0).andExpect(status().isForbidden());
        request(platform, "registry", "UNKNOWN", "", 0).andExpect(status().isBadRequest());
    }

    @Test
    void singlePendingCaseDoesNotTurnOtherEmptyFiltersIntoHttpErrors() throws Exception {
        UUID org = organization();
        var platform = actor(PLATFORM_ORGANIZATION_ID, "SUPER_ADMIN");
        var admin = actor(org, "ADMIN");
        UUID pending = create(actor(org, "USER"), true);
        assertPage(request(platform, "registry", "PENDING", "", 0), 0, pending);
        for (String state : List.of("NEEDS_INFORMATION", "HELD", "REJECTED", "APPROVED_REVIEW_ONLY", "WITHDRAWN")) {
            assertPage(request(platform, "registry", state, "", 0), 0);
        }
        assertPage(request(admin, "registry", "SUBMITTED", "", 0), 0, pending);
        assertPage(request(admin, "registry", "DRAFT", "", 0), 0);
        assertPage(request(admin, "registry", "WITHDRAWN", "", 0), 0);
        mvc.perform(get("/api/admin/erasure/registry-summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platform.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pending").value(1)).andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.needsInformation").value(0));
        mvc.perform(get("/api/admin/erasure/history")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platform.token())
                .param("query", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void filtersReturnExactRowsWithoutCrossTenantOrReviewMetadataLeaks() throws Exception {
        UUID org = organization(), otherOrg = organization();
        var platform = actor(PLATFORM_ORGANIZATION_ID, "SUPER_ADMIN");
        var admin = actor(org, "ADMIN");
        var reviewer = actor(org, "ADMIN");
        var legal = actor(org, "ADMIN");
        grant(reviewer, "REVIEW");
        grant(legal, "LEGAL");
        UUID pending = create(actor(org, "USER"), true);
        UUID held = create(actor(org, "USER"), true);
        UUID clarification = create(actor(org, "USER"), true);
        UUID rejected = create(actor(org, "USER"), true);
        UUID approved = create(actor(org, "USER"), true);
        UUID draft = create(actor(org, "USER"), false);
        var withdrawing = actor(org, "USER");
        UUID withdrawn = create(withdrawing, true);
        mutations.withdraw(withdrawn, 1, "r254-withdraw-" + UUID.randomUUID(), withdrawing.principal());
        UUID foreign = create(actor(otherOrg, "USER"), true);
        reviews.hold(held, 0, "r254-hold-" + UUID.randomUUID(), new ErasureReviewTypes.HoldBody("LEGAL_REQUEST"), legal.principal());
        reviews.decide(clarification, 0, "r254-clarify-" + UUID.randomUUID(),
                new ErasureReviewTypes.DecisionBody("NEEDS_INFORMATION", "INSUFFICIENT_INFORMATION"), reviewer.principal());
        reviews.decide(rejected, 0, "r254-reject-" + UUID.randomUUID(),
                new ErasureReviewTypes.DecisionBody("REJECTED", "INSUFFICIENT_AUTHORITY"), reviewer.principal());
        reviews.decide(approved, 0, "r254-approve-" + UUID.randomUUID(),
                new ErasureReviewTypes.DecisionBody("APPROVED_REVIEW_ONLY", "VERIFIED_OWNER_REQUEST"), reviewer.principal());

        assertPage(request(platform, "registry", "ALL", "", 0), 0, pending, held, clarification, rejected, approved, withdrawn, foreign);
        assertPage(request(platform, "registry", "PENDING", "", 0), 0, pending, held, foreign);
        assertPage(request(platform, "registry", "ATTENTION", "", 0), 0, pending, held, clarification, foreign);
        assertPage(request(platform, "registry", "HELD", "", 0), 0, held);
        assertPage(request(platform, "registry", "NEEDS_INFORMATION", "", 0), 0, clarification);
        assertPage(request(platform, "registry", "REJECTED", "", 0), 0, rejected);
        assertPage(request(platform, "registry", "APPROVED_REVIEW_ONLY", "", 0), 0, approved);
        assertPage(request(platform, "registry", "WITHDRAWN", "", 0), 0, withdrawn);
        assertPage(request(platform, "registry", "SUBMITTED", "", 0), 0);
        assertPage(request(platform, "registry", "DRAFT", "", 0), 0);
        assertPage(request(platform, "registry", "REJECTED", pending.toString(), 0), 0);
        assertPage(request(platform, "registry", "ALL", "", 1), 1);
        var globalRow = request(platform, "registry", "PENDING", pending.toString(), 0);
        assertPage(globalRow, 0, pending);
        globalRow.andExpect(jsonPath("$.items[0].requester").hasJsonPath())
                .andExpect(jsonPath("$.items[0].requester").value(nullValue()))
                .andExpect(jsonPath("$.items[0].reviewVisible").value(true));

        assertPage(request(admin, "registry", "ALL", "", 0), 0, pending, held, clarification, rejected, approved, withdrawn, draft);
        assertPage(request(admin, "registry", "SUBMITTED", "", 0), 0, pending, held, clarification, rejected, approved);
        assertPage(request(admin, "registry", "DRAFT", "", 0), 0, draft);
        assertPage(request(admin, "registry", "WITHDRAWN", "", 0), 0, withdrawn);
        for (String state : List.of("PENDING", "ATTENTION", "HELD", "NEEDS_INFORMATION", "REJECTED", "APPROVED_REVIEW_ONLY")) {
            assertPage(request(admin, "registry", state, "", 0), 0);
        }
        assertPage(request(admin, "registry", "ALL", foreign.toString(), 0), 0);
        var tenantRow = request(admin, "registry", "ALL", held.toString(), 0);
        assertPage(tenantRow, 0, held);
        tenantRow.andExpect(jsonPath("$.items[0].reviewVisible").value(false))
                .andExpect(jsonPath("$.items[0].reviewState").hasJsonPath())
                .andExpect(jsonPath("$.items[0].reviewState").value(nullValue()))
                .andExpect(jsonPath("$.items[0].holdActive").value(false));
        assertPage(request(reviewer, "review-queue", "HELD", "", 0), 0, held);
        assertPage(request(reviewer, "review-queue", "ALL", foreign.toString(), 0), 0);
        // A platform read and a preliminary approval never claim physical deletion.
        mvc.perform(get("/api/admin/erasure/registry/" + approved)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platform.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.executionState").value("NOT_AVAILABLE"))
                .andExpect(jsonPath("$.completionProven").value(false));
    }
}
