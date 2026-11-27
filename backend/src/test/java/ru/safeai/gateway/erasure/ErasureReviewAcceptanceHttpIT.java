// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ErasureReviewAcceptanceHttpIT.java
package ru.safeai.gateway.erasure;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.safeai.gateway.auth.service.AuthCookieProperties;
import ru.safeai.gateway.auth.service.JwtService;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R2.6: actual controllers, JWT/CSRF filters, migrated PostgreSQL and V105 triggers. */
@ActiveProfiles("test")
class ErasureReviewAcceptanceHttpIT extends AbstractPostgresIntegrationTest {
    private static final String PROOF = "11".repeat(32); // isolated test DB only
    private static final String SELF = "/api/erasure/requests";
    private static final String ADMIN = "/api/admin/erasure";
    private static final String APPROVE = "{\"decision\":\"APPROVED_REVIEW_ONLY\",\"reasonCode\":\"VERIFIED_OWNER_REQUEST\"}";
    private static final String CLARIFY = "{\"decision\":\"NEEDS_INFORMATION\",\"reasonCode\":\"INSUFFICIENT_INFORMATION\"}";
    private static final String REJECT = "{\"decision\":\"REJECTED\",\"reasonCode\":\"INSUFFICIENT_AUTHORITY\"}";
    private static final String HOLD = "{\"reasonCode\":\"LEGAL_REQUEST\"}";

    @DynamicPropertySource
    static void acceptanceProperties(DynamicPropertyRegistry registry) {
        registry.add("safeai.erasure.review.actor-proof-key", () -> PROOF);
        registry.add("safeai.erasure.requests.enabled", () -> "true");
        registry.add("safeai.erasure.approvals.enabled", () -> "true");
        registry.add("safeai.chat.erasure.accept-enabled", () -> "false");
        registry.add("safeai.chat.erasure.executor-enabled", () -> "false");
        registry.add("safeai.knowledge.deletion.accept-enabled", () -> "false");
        registry.add("safeai.knowledge.deletion.executor-enabled", () -> "false");
        registry.add("safeai.knowledge.base-deletion.accept-enabled", () -> "false");
        registry.add("safeai.knowledge.base-deletion.executor-enabled", () -> "false");
    }

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtService jwt;
    @Autowired AuthCookieProperties cookies;
    private MockMvc mvc;
    private Cookie csrf;

    @BeforeEach
    void prepare() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret)
                VALUES (1,decode(?,'hex')) ON CONFLICT(key_id) DO UPDATE SET secret=EXCLUDED.secret
                """, PROOF);
        // Real cookie bootstrap; no filter bypass or test-only CSRF exemption.
        csrf = Objects.requireNonNull(
                mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                        .andReturn().getResponse().getCookie(cookies.csrfTokenName()),
                "CSRF bootstrap must return the configured cookie");
        assertThat(csrf.getValue()).isNotBlank();
    }

    private record Actor(UUID id, UUID org, String token) { }

    private UUID org() {
        UUID id = UUID.randomUUID();
        insertOrganization(id, "R26 " + id, true);
        return id;
    }

    private Actor actor(UUID org, String role) {
        UUID id = UUID.randomUUID();
        insertUser(id, org, "r26-" + id + "@example.test", true, role, Instant.now().minusSeconds(600));
        return new Actor(id, org, jwt.generateToken(new AccessTokenSubject(id, org, 0, 0, Set.of(role))));
    }

    private void grant(Actor actor, String authority) {
        // Existing V105 authority model; schema-owner fixture ONLY. No new runtime grants.
        jdbcTemplate.update("""
                INSERT INTO public.erasure_review_authorities_v105(organization_id,user_id,authority,enabled)
                VALUES (?,?,?,true)
                """, actor.org(), actor.id(), authority);
    }

    private ResultActions read(Actor actor, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token()));
    }

    private static String key() { return "r26-" + UUID.randomUUID(); }

    private ResultActions write(Actor actor, String path, Long version, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token())
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private UUID submitted(Actor owner) throws Exception {
        String target = """
                {"resourceType":"USER_ACCOUNT","resourceId":"%s","operation":"DELETE_ACCOUNT",
                 "scope":"PERSONAL_DATA_AND_ACCESS"}
                """.formatted(owner.id());
        String preview = body(write(owner, "/api/erasure/previews", null, null, target)
                .andExpect(status().isOk()).andExpect(jsonPath("$.executionAvailable").value(false)));
        String draft = """
                {"target":%s,"previewId":"%s","previewDigest":"%s","expectedResourceVersion":%s,
                 "reasonCode":"SELF_REQUEST"}
                """.formatted(target, JsonPath.read(preview, "$.previewId"),
                    JsonPath.read(preview, "$.previewDigest"), JsonPath.read(preview, "$.expectedResourceVersion"));
        String created = body(write(owner, SELF, null, key(), draft).andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("DRAFT")));
        String requestId = Objects.requireNonNull(JsonPath.read(created, "$.requestId"),
                "Draft response must contain requestId");
        UUID id = UUID.fromString(requestId);
        write(owner, SELF + "/" + id + "/submit", 0L, key(), null)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("SUBMITTED"))
                .andExpect(jsonPath("$.executionStarted").value(false))
                .andExpect(jsonPath("$.completionProven").value(false));
        return id;
    }

    private static String review(UUID id) { return ADMIN + "/reviews/" + id; }
    private static String personal(UUID id) { return SELF + "/" + id; }

    private void state(ResultActions response, String state, long version, boolean hold) throws Exception {
        response.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.baseState").value("SUBMITTED"))
                .andExpect(jsonPath("$.reviewState").value(state)).andExpect(jsonPath("$.version").value(Math.toIntExact(version)))
                .andExpect(jsonPath("$.holdActive").value(hold))
                .andExpect(jsonPath("$.executionAvailable").value(false))
                .andExpect(jsonPath("$.completionProven").value(false));
    }

    private void events(Actor actor, UUID id, String... expected) throws Exception {
        String json = body(read(actor, personal(id) + "/review-events").andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store"))));
        List<String> events = JsonPath.read(json, "$.items[*].eventCode");
        assertThat(events).containsExactly(expected);
    }

    private void counts(Actor actor, String field, int count) throws Exception {
        read(actor, SELF + "/notifications").andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$." + field).value(count));
    }

    private Map<String, Long> destructiveCounts() {
        return Map.of(
                "chat_erasure_operations_v86", Objects.requireNonNull(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM public.chat_erasure_operations_v86", Long.class)),
                "knowledge_document_deletions_v78", Objects.requireNonNull(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM public.knowledge_document_deletions_v78", Long.class)),
                "knowledge_base_deletions_v82", Objects.requireNonNull(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM public.knowledge_base_deletions_v82", Long.class)));
    }

    @Test
    void clarificationReplayAndApprovalAreConsistentAcrossHttpProjections() throws Exception {
        UUID org = org();
        Actor owner = actor(org, "USER"), reviewer = actor(org, "ADMIN");
        Actor platform = actor(PLATFORM_ORGANIZATION_ID, "SUPER_ADMIN");
        grant(reviewer, "REVIEW");
        var before = destructiveCounts();
        UUID id = submitted(owner);
        state(read(owner, personal(id) + "/review"), "PENDING", 0, false);
        counts(reviewer, "reviewCount", 1);
        counts(platform, "registryCount", 1);
        String clarifyKey = key();
        state(write(reviewer, review(id) + "/decision", 0L, clarifyKey, CLARIFY), "NEEDS_INFORMATION", 1, false);
        state(write(reviewer, review(id) + "/decision", 0L, clarifyKey, CLARIFY), "NEEDS_INFORMATION", 1, false);
        write(reviewer, review(id) + "/decision", 0L, clarifyKey, APPROVE).andExpect(status().isConflict());
        counts(reviewer, "reviewCount", 0);
        counts(platform, "registryCount", 0);
        read(owner, SELF + "/notifications").andExpect(status().isOk())
                .andExpect(jsonPath("$.personal[0].requestId").value(id.toString()))
                .andExpect(jsonPath("$.personal[0].eventCode").value("NEEDS_INFORMATION"));
        read(platform, ADMIN + "/registry-summary").andExpect(status().isOk())
                .andExpect(jsonPath("$.needsInformation").value(1));
        write(owner, personal(id) + "/provide-information", 0L, key(), null).andExpect(status().isConflict());
        String informationKey = key();
        state(write(owner, personal(id) + "/provide-information", 1L, informationKey, null), "PENDING", 2, false);
        state(write(owner, personal(id) + "/provide-information", 1L, informationKey, null), "PENDING", 2, false);
        counts(reviewer, "reviewCount", 1);
        counts(platform, "registryCount", 1);
        write(reviewer, review(id) + "/decision", 1L, key(), APPROVE).andExpect(status().isConflict());
        state(write(reviewer, review(id) + "/decision", 2L, key(), APPROVE), "APPROVED_REVIEW_ONLY", 3, false);
        events(owner, id, "SUBMITTED", "NEEDS_INFORMATION", "INFORMATION_PROVIDED", "APPROVED_REVIEW_ONLY");
        counts(reviewer, "reviewCount", 0);
        counts(platform, "registryCount", 0);
        read(platform, ADMIN + "/registry/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewState").value("APPROVED_REVIEW_ONLY"))
                .andExpect(jsonPath("$.executionState").value("NOT_AVAILABLE"))
                .andExpect(jsonPath("$.completionProven").value(false));
        read(owner, SELF + "/notifications").andExpect(status().isOk())
                .andExpect(jsonPath("$.personal[*].eventCode").value(hasItems("APPROVED_REVIEW_ONLY", "NEEDS_INFORMATION")));
        assertThat(destructiveCounts()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM public.users WHERE id=?", Boolean.class, owner.id())).isTrue();
    }

    @Test
    void legalHoldInvalidatesApprovalAndRequiresDifferentLegalActor() throws Exception {
        UUID org = org();
        Actor owner = actor(org, "USER"), reviewer = actor(org, "ADMIN");
        Actor legal = actor(org, "ADMIN"), otherLegal = actor(org, "ADMIN");
        Actor platform = actor(PLATFORM_ORGANIZATION_ID, "SUPER_ADMIN");
        grant(reviewer, "REVIEW"); grant(legal, "LEGAL"); grant(otherLegal, "LEGAL");
        var before = destructiveCounts();
        UUID id = submitted(owner);
        state(write(reviewer, review(id) + "/decision", 0L, key(), APPROVE), "APPROVED_REVIEW_ONLY", 1, false);
        write(reviewer, review(id) + "/holds", 1L, key(), HOLD).andExpect(status().isForbidden());
        state(write(legal, review(id) + "/holds", 1L, key(), HOLD), "PENDING", 2, true);
        read(platform, ADMIN + "/registry-summary").andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(1)).andExpect(jsonPath("$.pending").value(1));
        write(legal, review(id) + "/decision", 2L, key(), APPROVE).andExpect(status().isForbidden());
        write(reviewer, review(id) + "/decision", 2L, key(), APPROVE).andExpect(status().isConflict());
        write(legal, review(id) + "/holds/release", 2L, key(), null).andExpect(status().isForbidden());
        state(write(otherLegal, review(id) + "/holds/release", 2L, key(), null), "PENDING", 3, false);
        read(platform, ADMIN + "/registry-summary").andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(0)).andExpect(jsonPath("$.pending").value(1));
        state(write(reviewer, review(id) + "/decision", 3L, key(), REJECT), "REJECTED", 4, false);
        events(owner, id, "SUBMITTED", "APPROVED_REVIEW_ONLY", "HOLD_APPLIED", "HOLD_RELEASED", "REJECTED");
        write(legal, review(id) + "/holds", 4L, key(), HOLD).andExpect(status().isConflict());
        assertThat(destructiveCounts()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM public.users WHERE id=?", Boolean.class, owner.id())).isTrue();
    }

    @Test
    void authorityTenantSelfAndRevocationAreEnforcedOnDirectRequests() throws Exception {
        UUID org = org();
        Actor owner = actor(org, "ADMIN"), stranger = actor(org, "USER"), reviewer = actor(org, "ADMIN");
        Actor noGrant = actor(org, "ADMIN"), legal = actor(org, "ADMIN");
        Actor foreign = actor(org(), "ADMIN"), platform = actor(PLATFORM_ORGANIZATION_ID, "SUPER_ADMIN");
        grant(owner, "REVIEW"); grant(reviewer, "REVIEW"); grant(legal, "LEGAL"); grant(foreign, "REVIEW");
        UUID id = submitted(owner);
        counts(owner, "reviewCount", 0);
        read(legal, SELF + "/notifications").andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(nullValue()))
                .andExpect(jsonPath("$.review").isEmpty());
        for (Actor denied : List.of(owner, noGrant, platform, legal)) {
            write(denied, review(id) + "/decision", 0L, key(), APPROVE).andExpect(status().isForbidden());
        }
        read(foreign, review(id)).andExpect(status().isNotFound());
        write(foreign, review(id) + "/decision", 0L, key(), APPROVE).andExpect(status().isNotFound());
        read(stranger, personal(id) + "/review").andExpect(status().isNotFound());
        write(stranger, personal(id) + "/provide-information", 0L, key(), null).andExpect(status().isNotFound());
        write(stranger, personal(UUID.randomUUID()) + "/provide-information", 0L, key(), null).andExpect(status().isNotFound());
        jdbcTemplate.update("""
                UPDATE public.erasure_review_authorities_v105 SET enabled=false
                WHERE organization_id=? AND user_id=? AND authority='REVIEW'
                """, org, reviewer.id());
        write(reviewer, review(id) + "/decision", 0L, key(), APPROVE).andExpect(status().isForbidden());
        read(reviewer, ADMIN + "/review-queue").andExpect(status().isForbidden());
        read(reviewer, SELF + "/notifications").andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(nullValue()));
        // A stale JWT must not let a disabled actor use the remaining grant.
        jdbcTemplate.update("UPDATE public.users SET enabled=false WHERE id=?", foreign.id());
        int disabled = read(foreign, ADMIN + "/review-queue").andReturn().getResponse().getStatus();
        assertThat(disabled).isIn(401, 403);
        events(owner, id, "SUBMITTED");
    }

    private Cookie accessCookie(Actor actor) {
        Cookie access = new Cookie(cookies.accessTokenName(), actor.token());
        access.setHttpOnly(true);
        access.setSecure(cookies.secure());
        access.setPath("/");
        return access;
    }

    // No Authorization header here: Spring Security must enforce its cookie CSRF path.
    private MockHttpServletRequestBuilder cookieDecision(Actor actor, UUID id) {
        return post(review(id) + "/decision").secure(cookies.secure())
                .cookie(accessCookie(actor))
                .header("If-Match", "\"0\"").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(APPROVE);
    }

    @Test
    void malformedInputsAndMissingCsrfNeverCreateReviewEvents() throws Exception {
        UUID org = org();
        Actor owner = actor(org, "USER"), reviewer = actor(org, "ADMIN"), legal = actor(org, "ADMIN");
        grant(reviewer, "REVIEW"); grant(legal, "LEGAL");
        UUID id = submitted(owner);
        for (String invalid : List.of("{}", "{\"decision\":null}", "{\"decision\":\"APPROVED_REVIEW_ONLY\"}",
                "{\"decision\":\"APPROVED_REVIEW_ONLY\",\"reasonCode\":\"INSUFFICIENT_INFORMATION\"}")) {
            write(reviewer, review(id) + "/decision", 0L, key(), invalid).andExpect(status().isBadRequest());
        }
        write(legal, review(id) + "/holds", 0L, key(), "{}").andExpect(status().isBadRequest());
        write(reviewer, review(id) + "/decision", null, key(), APPROVE).andExpect(status().isBadRequest());
        write(reviewer, review(id) + "/decision", 0L, null, APPROVE).andExpect(status().isBadRequest());
        state(read(owner, personal(id) + "/review"), "PENDING", 0, false);
        events(owner, id, "SUBMITTED");

        // Confirm that this is a real, authorized cookie identity, not an anonymous request.
        state(mvc.perform(get(review(id)).secure(cookies.secure()).cookie(accessCookie(reviewer))),
                "PENDING", 0, false);
        var before = destructiveCounts();
        for (MockHttpServletRequestBuilder invalid : List.of(
                cookieDecision(reviewer, id),
                cookieDecision(reviewer, id).cookie(csrf),
                cookieDecision(reviewer, id).header("X-XSRF-TOKEN", csrf.getValue()),
                cookieDecision(reviewer, id).cookie(csrf).header("X-XSRF-TOKEN", "invalid-" + UUID.randomUUID()))) {
            mvc.perform(invalid).andExpect(status().isForbidden());
            state(read(owner, personal(id) + "/review"), "PENDING", 0, false);
            events(owner, id, "SUBMITTED");
            assertThat(destructiveCounts()).isEqualTo(before);
        }
    }

    @Test
    void validCookieCsrfPermitsOnlyAuthorizedReview() throws Exception {
        UUID org = org();
        Actor owner = actor(org, "USER"), reviewer = actor(org, "ADMIN"), noGrant = actor(org, "ADMIN");
        grant(reviewer, "REVIEW");
        var before = destructiveCounts();
        UUID id = submitted(owner);
        mvc.perform(cookieDecision(noGrant, id).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
        state(read(owner, personal(id) + "/review"), "PENDING", 0, false);
        events(owner, id, "SUBMITTED");
        state(mvc.perform(cookieDecision(reviewer, id).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())),
                "APPROVED_REVIEW_ONLY", 1, false);
        events(owner, id, "SUBMITTED", "APPROVED_REVIEW_ONLY");
        assertThat(destructiveCounts()).isEqualTo(before);
    }

    @Test
    void bearerWithoutCsrfStillRequiresIndependentAuthority() throws Exception {
        UUID org = org();
        Actor owner = actor(org, "USER"), reviewer = actor(org, "ADMIN"), noGrant = actor(org, "ADMIN");
        grant(reviewer, "REVIEW");
        var before = destructiveCounts();
        UUID id = submitted(owner);
        // Explicit header authentication is not ambient cookie authentication.
        mvc.perform(post(review(id) + "/decision").header(HttpHeaders.AUTHORIZATION, "Bearer " + noGrant.token())
                .header("If-Match", "\"0\"").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(APPROVE)).andExpect(status().isForbidden());
        state(read(owner, personal(id) + "/review"), "PENDING", 0, false);
        events(owner, id, "SUBMITTED");
        state(mvc.perform(post(review(id) + "/decision").header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewer.token())
                .header("If-Match", "\"0\"").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(APPROVE)), "APPROVED_REVIEW_ONLY", 1, false);
        events(owner, id, "SUBMITTED", "APPROVED_REVIEW_ONLY");
        assertThat(destructiveCounts()).isEqualTo(before);
    }

    @Test
    void competingDecisionsCommitExactlyOneTransition() throws Exception {
        UUID org = org();
        Actor owner = actor(org, "USER"), first = actor(org, "ADMIN"), second = actor(org, "ADMIN");
        grant(first, "REVIEW"); grant(second, "REVIEW");
        UUID id = submitted(owner);
        var before = destructiveCounts();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var approval = pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    return write(first, review(id) + "/decision", 0L, key(), APPROVE)
                            .andReturn().getResponse().getStatus();
                });
                var rejection = pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    return write(second, review(id) + "/decision", 0L, key(), REJECT)
                            .andReturn().getResponse().getStatus();
                });
                start.countDown();
                int a = approval.get(30, TimeUnit.SECONDS), b = rejection.get(30, TimeUnit.SECONDS);
                assertThat(List.of(a, b)).containsExactlyInAnyOrder(200, 409);
                String winner = a == 200 ? "APPROVED_REVIEW_ONLY" : "REJECTED";
                state(read(owner, personal(id) + "/review"), winner, 1, false);
                events(owner, id, "SUBMITTED", winner);
                assertThat(destructiveCounts()).isEqualTo(before);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

}
