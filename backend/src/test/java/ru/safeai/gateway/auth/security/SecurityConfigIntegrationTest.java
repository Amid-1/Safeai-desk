package ru.safeai.gateway.auth.security;

import jakarta.servlet.http.Cookie;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.auth.controller.AuthController;
import ru.safeai.gateway.auth.controller.CsrfController;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.service.AuthCookieService;
import ru.safeai.gateway.auth.service.AuthService;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiErrorResponseWriter;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.JwtCodecConfiguration;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.JwtProperties;
import ru.safeai.gateway.common.security.JwtRsaKeyRing;
import ru.safeai.gateway.common.security.PasswordEncodingConfiguration;
import ru.safeai.gateway.common.security.RequestIdFilter;
import ru.safeai.gateway.common.security.RestAccessDeniedHandler;
import ru.safeai.gateway.common.security.RestAuthenticationEntryPoint;
import ru.safeai.gateway.common.security.SafeAiJwtAuthenticationConverter;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.service.UserSecurityStatus;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test настоящей SecurityConfig.
 *
 * <p>Использует production JWT codec, JWT converter,
 * security handlers, CSRF, cookie bearer fallback,
 * RequestIdFilter и UserStatusFilter.</p>
 */
@WebMvcTest(useDefaultFilters = false)
@Import({
        AuthController.class,
        CsrfController.class,
        SecurityConfigIntegrationTest.SecurityProbeController.class,

        SecurityConfig.class,
        PasswordEncodingConfiguration.class,
        SafeAiJwtAuthenticationConverter.class,
        AuthCookieService.class,
        UserStatusFilter.class,

        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        ApiErrorResponseWriter.class,
        ApiErrorResponseFactory.class,
        RequestIdFilter.class,

        JwtCodecConfiguration.class,
        JwtRsaKeyRing.class,
        JwtService.class,

        SecurityConfigIntegrationTest.TestClockConfiguration.class
})
@TestPropertySource(properties = {
        "app.security.jwt.expiration-minutes=15",
        "app.security.jwt.issuer=https://issuer.safeai.test",
        "app.security.jwt.audience=safeai-api",

        "safeai.auth.cookies.secure=false",
        "safeai.auth.cookies.same-site=Lax",
        "safeai.auth.cookies.access-token-max-age=15m",
        "safeai.auth.cookies.refresh-token-max-age=7d",
        "safeai.auth.cookies.refresh-token-absolute-max-age=30d",
        "safeai.auth.cookies.reuse-detection-retention=7d",
        "safeai.auth.cookies.access-token-name=safeai-access",
        "safeai.auth.cookies.refresh-token-name=safeai-refresh",

        "safeai.cors.allowed-origins=https://app.safeai.test",
        "safeai.security.client-ip.trusted-proxy-cidrs="
})
@ActiveProfiles("test")
class SecurityConfigIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID OTHER_USER_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    private static final String EMAIL =
            "user@test.com";

    private static final long TOKEN_VERSION =
            7L;

    private static final long ORGANIZATION_AUTH_VERSION =
            11L;

    private static final String ACCESS_COOKIE_NAME =
            "safeai-access";

    private static final String CSRF_COOKIE_NAME =
            "XSRF-TOKEN";

    private static final String CSRF_HEADER_NAME =
            "X-XSRF-TOKEN";

    private static final String CLIENT_REQUEST_ID =
            "security-integration-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserStatusCacheService
            userStatusCacheService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping("/api/admin/security-probe")
        Map<String, String> adminProbe() {
            return Map.of(
                    "scope",
                    "admin"
            );
        }

        @GetMapping("/api/chats/security-probe")
        Map<String, String> chatProbe() {
            return Map.of(
                    "scope",
                    "chat"
            );
        }

        @GetMapping("/actuator/security-probe")
        Map<String, String> actuatorProbe() {
            return Map.of(
                    "scope",
                    "actuator"
            );
        }

        @PostMapping("/api/organizations")
        Map<String, String> createOrganizationProbe() {
            return Map.of(
                    "result",
                    "created"
            );
        }
    }

    @Test
    void csrfEndpointIsPublicAndCreatesReadableCookie()
            throws Exception {

        MvcResult result =
                mockMvc.perform(
                                get("/api/auth/csrf")
                                        .header(
                                                RequestIdFilter
                                                        .REQUEST_ID_HEADER,
                                                CLIENT_REQUEST_ID
                                        )
                        )
                        .andExpect(
                                status().isOk()
                        )
                        .andExpect(
                                header().string(
                                        HttpHeaders.CACHE_CONTROL,
                                        "no-store"
                                )
                        )
                        .andExpect(
                                header().exists(
                                        RequestIdFilter
                                                .REQUEST_ID_HEADER
                                )
                        )
                        .andExpect(
                                jsonPath("$.headerName")
                                        .value(
                                                CSRF_HEADER_NAME
                                        )
                        )
                        .andExpect(
                                jsonPath("$.parameterName")
                                        .value("_csrf")
                        )
                        .andExpect(
                                jsonPath("$.token")
                                        .isNotEmpty()
                        )
                        .andReturn();

        assertServerGeneratedRequestId(
                result
        );

        Cookie csrfCookie =
                requireCsrfCookie(
                        result
                );

        assertThat(
                csrfCookie.getValue()
        ).isNotBlank();

        assertThat(
                csrfCookie.isHttpOnly()
        ).isFalse();

        assertThat(
                csrfCookie.getSecure()
        ).isFalse();

        assertThat(
                csrfCookie.getPath()
        ).isEqualTo("/");

        assertThat(
                csrfCookie.getAttribute(
                        "SameSite"
                )
        ).isEqualTo("Lax");

        assertThat(
                result.getResponse()
                        .getHeaders(
                                HttpHeaders.SET_COOKIE
                        )
        ).anySatisfy(headerValue ->
                assertThat(headerValue)
                        .contains(
                                CSRF_COOKIE_NAME + "="
                        )
                        .contains(
                                "Path=/"
                        )
                        .doesNotContain(
                                "HttpOnly"
                        )
        );

        verifyNoInteractions(
                authService,
                userStatusCacheService
        );
    }

    @Test
    void refreshWithoutCsrfReturns403BeforeController()
            throws Exception {

        mockMvc.perform(
                        post("/api/auth/refresh")
                )
                .andExpect(
                        status().isForbidden()
                )
                .andExpect(
                        content()
                                .contentTypeCompatibleWith(
                                        MediaType.APPLICATION_JSON
                                )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(403)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("FORBIDDEN")
                )
                .andExpect(
                        jsonPath("$.path")
                                .value(
                                        "/api/auth/refresh"
                                )
                );

        verifyNoInteractions(
                authService,
                userStatusCacheService
        );
    }

    @Test
    void refreshWithRealCsrfCookieAndHeaderReachesController()
            throws Exception {

        CsrfContext csrf =
                obtainCsrf();

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .cookie(
                                        csrf.cookie()
                                )
                                .header(
                                        CSRF_HEADER_NAME,
                                        csrf.cookie()
                                                .getValue()
                                )
                )
                .andExpect(
                        status().isNoContent()
                );

        verify(authService)
                .refresh(
                        any(),
                        any()
                );

        verifyNoMoreInteractions(
                authService
        );

        verifyNoInteractions(
                userStatusCacheService
        );
    }

    @Test
    void publicAuthEndpointIgnoresInvalidBearerToken()
            throws Exception {

        CsrfContext csrf =
                obtainCsrf();

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer definitely-not-a-jwt"
                                )
                                .cookie(
                                        csrf.cookie()
                                )
                                .header(
                                        CSRF_HEADER_NAME,
                                        csrf.cookie()
                                                .getValue()
                                )
                )
                .andExpect(
                        status().isNoContent()
                );

        verify(authService)
                .refresh(
                        any(),
                        any()
                );

        verifyNoMoreInteractions(
                authService
        );

        verifyNoInteractions(
                userStatusCacheService
        );
    }

    @Test
    void meWithoutTokenReturnsJson401()
            throws Exception {

        mockMvc.perform(
                        get("/api/auth/me")
                )
                .andExpect(
                        status().isUnauthorized()
                )
                .andExpect(
                        content()
                                .contentTypeCompatibleWith(
                                        MediaType.APPLICATION_JSON
                                )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                "no-store"
                        )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(401)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("UNAUTHORIZED")
                )
                .andExpect(
                        jsonPath("$.path")
                                .value("/api/auth/me")
                );

        verifyNoInteractions(
                authService,
                userStatusCacheService
        );
    }

    @Test
    void validBearerTokenIsDecodedConvertedAndAccepted()
            throws Exception {

        stubValidStatus();

        when(
                authService.getCurrentUser(
                        any(
                                SafeAiUserPrincipal.class
                        )
                )
        ).thenReturn(
                currentUserResponse()
        );

        MvcResult result =
                mockMvc.perform(
                                get("/api/auth/me")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(
                                                        validUserToken()
                                                )
                                        )
                                        .header(
                                                RequestIdFilter
                                                        .REQUEST_ID_HEADER,
                                                CLIENT_REQUEST_ID
                                        )
                        )
                        .andExpect(
                                status().isOk()
                        )
                        .andExpect(
                                jsonPath("$.id")
                                        .value(
                                                USER_ID.toString()
                                        )
                        )
                        .andExpect(
                                jsonPath("$.organizationId")
                                        .value(
                                                ORGANIZATION_ID.toString()
                                        )
                        )
                        .andExpect(
                                jsonPath("$.email")
                                        .value(EMAIL)
                        )
                        .andExpect(
                                header().exists(
                                        RequestIdFilter
                                                .REQUEST_ID_HEADER
                                )
                        )
                        .andExpect(
                                header().string(
                                        "Content-Security-Policy",
                                        "default-src 'self'; "
                                                + "frame-ancestors 'none'; "
                                                + "object-src 'none'"
                                )
                        )
                        .andExpect(
                                header().string(
                                        "X-Frame-Options",
                                        "DENY"
                                )
                        )
                        .andExpect(
                                header().string(
                                        "X-Content-Type-Options",
                                        "nosniff"
                                )
                        )
                        .andExpect(
                                header().doesNotExist(
                                        "Strict-Transport-Security"
                                )
                        )
                        .andReturn();

        assertServerGeneratedRequestId(
                result
        );

        assertThat(
                result.getRequest()
                        .getSession(false)
        ).isNull();

        ArgumentCaptor<SafeAiUserPrincipal>
                principalCaptor =
                ArgumentCaptor.forClass(
                        SafeAiUserPrincipal.class
                );

        verify(authService)
                .getCurrentUser(
                        principalCaptor.capture()
                );

        SafeAiUserPrincipal principal =
                principalCaptor.getValue();

        assertThat(
                principal.getId()
        ).isEqualTo(USER_ID);

        assertThat(
                principal.getOrganizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                principal.getEmail()
        ).isNull();

        assertThat(
                principal.getTokenVersion()
        ).isEqualTo(
                TOKEN_VERSION
        );

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(
                ORGANIZATION_AUTH_VERSION
        );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_USER"
        );

        assertThat(
                principal.getPassword()
        ).isNull();

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoMoreInteractions(
                authService,
                userStatusCacheService
        );
    }

    @Test
    void validAccessCookieIsDecodedAndAccepted()
            throws Exception {

        stubValidStatus();

        when(
                authService.getCurrentUser(
                        any(
                                SafeAiUserPrincipal.class
                        )
                )
        ).thenReturn(
                currentUserResponse()
        );

        mockMvc.perform(
                        get("/api/auth/me")
                                .cookie(
                                        accessCookie(
                                                validUserToken()
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.id")
                                .value(
                                        USER_ID.toString()
                                )
                );

        verify(authService)
                .getCurrentUser(
                        any(
                                SafeAiUserPrincipal.class
                        )
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoMoreInteractions(
                authService,
                userStatusCacheService
        );
    }

    @Test
    void invalidAccessCookieReturnsJson401()
            throws Exception {

        mockMvc.perform(
                        get("/api/auth/me")
                                .cookie(
                                        accessCookie(
                                                "invalid-cookie-token"
                                        )
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                )
                .andExpect(
                        content()
                                .contentTypeCompatibleWith(
                                        MediaType.APPLICATION_JSON
                                )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                "no-store"
                        )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(401)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("UNAUTHORIZED")
                )
                .andExpect(
                        jsonPath("$.path")
                                .value("/api/auth/me")
                );

        verifyNoInteractions(
                authService,
                userStatusCacheService
        );
    }

    @Test
    void bearerHeaderTakesPrecedenceOverInvalidAccessCookie()
            throws Exception {

        stubValidStatus();

        when(
                authService.getCurrentUser(
                        any(
                                SafeAiUserPrincipal.class
                        )
                )
        ).thenReturn(
                currentUserResponse()
        );

        mockMvc.perform(
                        get("/api/auth/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validUserToken()
                                        )
                                )
                                .cookie(
                                        accessCookie(
                                                "invalid-cookie-token"
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                );

        verify(authService)
                .getCurrentUser(
                        any(
                                SafeAiUserPrincipal.class
                        )
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoMoreInteractions(
                authService,
                userStatusCacheService
        );
    }

    @Test
    void tokenWithInvalidSignatureReturns401()
            throws Exception {

        assertInvalidJwt(
                corruptSignature(
                        validUserToken()
                )
        );
    }

    @Test
    void expiredTokenReturns401()
            throws Exception {

        Instant now =
                Instant.now();

        TokenClaims claims =
                defaultClaims()
                        .withTimes(
                                now.minusSeconds(
                                        1_200
                                ),
                                now.minusSeconds(
                                        600
                                )
                        );

        assertInvalidJwt(
                encodeToken(
                        jwtEncoder,
                        claims
                )
        );
    }

    @Test
    void tokenWithWrongIssuerReturns401()
            throws Exception {

        assertInvalidJwt(
                encodeToken(
                        jwtEncoder,
                        defaultClaims()
                                .withWrongIssuer()
                )
        );
    }

    @Test
    void tokenWithWrongAudienceReturns401()
            throws Exception {

        assertInvalidJwt(
                encodeToken(
                        jwtEncoder,
                        defaultClaims()
                                .withAudience(
                                        List.of(
                                                "different-api"
                                        )
                                )
                )
        );
    }

    @Test
    void tokenWithoutOrganizationIdReturns401()
            throws Exception {

        assertInvalidJwt(
                encodeToken(
                        jwtEncoder,
                        defaultClaims()
                                .withoutOrganizationId()
                )
        );
    }

    @Test
    void tokenWithoutOrganizationAuthVersionReturns401()
            throws Exception {

        assertInvalidJwt(
                encodeToken(
                        jwtEncoder,
                        defaultClaims()
                                .withoutOrganizationAuthVersion()
                )
        );
    }

    @Test
    void tokenWithSubjectDifferentFromUserIdReturns401()
            throws Exception {

        assertInvalidJwt(
                encodeToken(
                        jwtEncoder,
                        defaultClaims()
                                .withSubject(
                                        OTHER_USER_ID
                                                .toString()
                                )
                )
        );
    }

    @Test
    void tokenWithUnknownRoleReturns401()
            throws Exception {

        assertInvalidJwt(
                encodeToken(
                        jwtEncoder,
                        defaultClaims()
                                .withRoles(
                                        List.of(
                                                "ROOT"
                                        )
                                )
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSecurityStatuses")
    void userStatusFilterRejectsInvalidSecurityState(
            InvalidSecurityStatusCase testCase
    ) throws Exception {

        when(
                userStatusCacheService
                        .getStatus(USER_ID)
        ).thenReturn(
                Optional.ofNullable(
                        testCase.securityStatus()
                )
        );

        mockMvc.perform(
                        get("/api/auth/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validUserToken()
                                        )
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                )
                .andExpect(
                        content()
                                .contentTypeCompatibleWith(
                                        MediaType.APPLICATION_JSON
                                )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                "no-store"
                        )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(401)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "TOKEN_REVOKED"
                                )
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Токен больше не действителен"
                                )
                )
                .andExpect(
                        jsonPath("$.path")
                                .value(
                                        "/api/auth/me"
                                )
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoMoreInteractions(
                userStatusCacheService
        );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void userRoleCannotAccessAdminEndpoint()
            throws Exception {

        stubValidStatus();

        mockMvc.perform(
                        get(
                                "/api/admin/security-probe"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validUserToken()
                                        )
                                )
                )
                .andExpect(
                        status().isForbidden()
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(403)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("FORBIDDEN")
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void adminRoleCanAccessAdminEndpoint()
            throws Exception {

        stubValidStatus();

        mockMvc.perform(
                        get(
                                "/api/admin/security-probe"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validToken(
                                                        Set.of(
                                                                "ADMIN"
                                                        )
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.scope")
                                .value("admin")
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void regularUserCanAccessAuthenticatedChatEndpoint()
            throws Exception {

        stubValidStatus();

        mockMvc.perform(
                        get(
                                "/api/chats/security-probe"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validUserToken()
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.scope")
                                .value("chat")
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void regularUserCannotAccessActuatorEndpoint()
            throws Exception {

        stubValidStatus();

        mockMvc.perform(
                        get(
                                "/actuator/security-probe"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validUserToken()
                                        )
                                )
                )
                .andExpect(
                        status().isForbidden()
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void superAdminCanAccessActuatorEndpoint()
            throws Exception {

        stubValidStatus();

        mockMvc.perform(
                        get(
                                "/actuator/security-probe"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validToken(
                                                        Set.of(
                                                                "SUPER_ADMIN"
                                                        )
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.scope")
                                .value("actuator")
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void organizationCreationWithAccessCookieRequiresCsrf()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/api/organizations"
                        )
                                .cookie(
                                        accessCookie(
                                                validToken(
                                                        Set.of(
                                                                "SUPER_ADMIN"
                                                        )
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isForbidden()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("FORBIDDEN")
                );

        verifyNoInteractions(
                authService,
                userStatusCacheService
        );
    }

    @Test
    void organizationCreationWithBearerHeaderDoesNotRequireCsrf()
            throws Exception {

        stubValidStatus();

        mockMvc.perform(
                        post(
                                "/api/organizations"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validToken(
                                                        Set.of(
                                                                "SUPER_ADMIN"
                                                        )
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.result")
                                .value("created")
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoMoreInteractions(
                userStatusCacheService
        );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void adminAccessCookieCannotCreateOrganizationWithValidCsrf()
            throws Exception {

        stubValidStatus();

        CsrfContext csrf =
                obtainCsrf();

        mockMvc.perform(
                        post(
                                "/api/organizations"
                        )
                                .cookie(
                                        csrf.cookie(),
                                        accessCookie(
                                                validToken(
                                                        Set.of(
                                                                "ADMIN"
                                                        )
                                                )
                                        )
                                )
                                .header(
                                        CSRF_HEADER_NAME,
                                        csrf.cookie()
                                                .getValue()
                                )
                )
                .andExpect(
                        status().isForbidden()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("FORBIDDEN")
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void superAdminAccessCookieCanCreateOrganizationWithValidCsrf()
            throws Exception {

        stubValidStatus();

        CsrfContext csrf =
                obtainCsrf();

        mockMvc.perform(
                        post(
                                "/api/organizations"
                        )
                                .cookie(
                                        csrf.cookie(),
                                        accessCookie(
                                                validToken(
                                                        Set.of(
                                                                "SUPER_ADMIN"
                                                        )
                                                )
                                        )
                                )
                                .header(
                                        CSRF_HEADER_NAME,
                                        csrf.cookie()
                                                .getValue()
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.result")
                                .value("created")
                );

        verify(userStatusCacheService)
                .getStatus(
                        USER_ID
                );

        verifyNoInteractions(
                authService
        );
    }

    @Test
    void optionsRequestIsPublic()
            throws Exception {

        mockMvc.perform(
                        options(
                                "/api/admin/security-probe"
                        )
                                .header(
                                        "Origin",
                                        "https://app.safeai.test"
                                )
                                .header(
                                        "Access-Control-Request-Method",
                                        "GET"
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        header().string(
                                "Access-Control-Allow-Origin",
                                "https://app.safeai.test"
                        )
                )
                .andExpect(
                        header().string(
                                "Access-Control-Allow-Credentials",
                                "true"
                        )
                );

        verifyNoInteractions(
                authService,
                userStatusCacheService
        );
    }

    private static java.util.stream.Stream<
            InvalidSecurityStatusCase>
    invalidSecurityStatuses() {

        return java.util.stream.Stream.of(
                new InvalidSecurityStatusCase(
                        "user status is absent",
                        null
                ),
                new InvalidSecurityStatusCase(
                        "user is disabled",
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                false,
                                true,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION
                        )
                ),
                new InvalidSecurityStatusCase(
                        "organization is disabled",
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                false,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION
                        )
                ),
                new InvalidSecurityStatusCase(
                        "token version is stale",
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                true,
                                TOKEN_VERSION + 1L,
                                ORGANIZATION_AUTH_VERSION
                        )
                ),
                new InvalidSecurityStatusCase(
                        "organization auth version is stale",
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                true,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION + 1L
                        )
                ),
                new InvalidSecurityStatusCase(
                        "organization id changed",
                        new UserSecurityStatus(
                                OTHER_ORGANIZATION_ID,
                                true,
                                true,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION
                        )
                )
        );
    }

    private void assertInvalidJwt(
            String token
    ) throws Exception {

        mockMvc.perform(
                        get("/api/auth/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(token)
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                )
                .andExpect(
                        content()
                                .contentTypeCompatibleWith(
                                        MediaType.APPLICATION_JSON
                                )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                "no-store"
                        )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(401)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "UNAUTHORIZED"
                                )
                )
                .andExpect(
                        jsonPath("$.path")
                                .value(
                                        "/api/auth/me"
                                )
                );

        verifyNoInteractions(
                authService,
                userStatusCacheService
        );
    }

    private void stubValidStatus() {
        when(
                userStatusCacheService
                        .getStatus(USER_ID)
        ).thenReturn(
                Optional.of(
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                true,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION
                        )
                )
        );
    }

    private CsrfContext obtainCsrf()
            throws Exception {

        MvcResult result =
                mockMvc.perform(
                                get("/api/auth/csrf")
                        )
                        .andExpect(
                                status().isOk()
                        )
                        .andReturn();

        return new CsrfContext(
                requireCsrfCookie(
                        result
                )
        );
    }

    private static Cookie requireCsrfCookie(
            MvcResult result
    ) {
        return Objects.requireNonNull(
                result.getResponse()
                        .getCookie(
                                CSRF_COOKIE_NAME
                        ),
                CSRF_COOKIE_NAME
                        + " cookie must be present"
        );
    }

    private void assertServerGeneratedRequestId(
            MvcResult result
    ) {
        String serverRequestId =
                result.getResponse()
                        .getHeader(
                                RequestIdFilter
                                        .REQUEST_ID_HEADER
                        );

        assertThat(serverRequestId)
                .isNotBlank()
                .isNotEqualTo(
                        CLIENT_REQUEST_ID
                );

        assertThat(
                UUID.fromString(
                        serverRequestId
                )
        ).isNotNull();

        assertThat(
                result.getRequest()
                        .getAttribute(
                                RequestIdFilter
                                        .REQUEST_ID_ATTRIBUTE
                        )
        ).isEqualTo(
                serverRequestId
        );

        assertThat(
                result.getRequest()
                        .getAttribute(
                                RequestIdFilter
                                        .CLIENT_REQUEST_ID_ATTRIBUTE
                        )
        ).isEqualTo(
                CLIENT_REQUEST_ID
        );
    }

    private Cookie accessCookie(
            String token
    ) {
        Cookie cookie =
                new Cookie(
                        ACCESS_COOKIE_NAME,
                        token
                );

        cookie.setHttpOnly(true);
        cookie.setPath("/");

        return cookie;
    }

    private String validUserToken() {
        return validToken(
                Set.of("USER")
        );
    }

    private String validToken(
            Set<String> roles
    ) {
        return jwtService
                .generateToken(
                        new AccessTokenSubject(
                                USER_ID,
                                ORGANIZATION_ID,
                                TOKEN_VERSION,
                                ORGANIZATION_AUTH_VERSION,
                                roles
                        )
                );
    }

    private CurrentUserResponse currentUserResponse() {
        return new CurrentUserResponse(
                USER_ID,
                ORGANIZATION_ID,
                EMAIL,
                "Test User",
                true,
                Set.of("USER")
        );
    }

    private TokenClaims defaultClaims() {
        Instant now =
                Instant.now();

        return new TokenClaims(
                jwtProperties.issuer(),
                List.of(
                        jwtProperties.audience()
                ),
                now.minusSeconds(5),
                now.plusSeconds(600),
                USER_ID.toString(),
                USER_ID,
                ORGANIZATION_ID,
                TOKEN_VERSION,
                ORGANIZATION_AUTH_VERSION,
                List.of("USER")
        );
    }

    private String encodeToken(
            JwtEncoder encoder,
            TokenClaims claims
    ) {
        JwsHeader headers =
                JwsHeader
                        .with(
                                SignatureAlgorithm.RS256
                        )
                        .type("JWT")
                        .keyId(
                                jwtProperties.activeKeyId()
                        )
                        .build();

        JwtClaimsSet.Builder builder =
                JwtClaimsSet.builder()
                        .issuer(
                                claims.issuer()
                        )
                        .audience(
                                claims.audience()
                        )
                        .issuedAt(
                                claims.issuedAt()
                        )
                        .expiresAt(
                                claims.expiresAt()
                        )
                        .subject(
                                claims.subject()
                        )
                        .id(
                                UUID.randomUUID()
                                        .toString()
                        );

        if (claims.userId() != null) {
            builder.claim(
                    "userId",
                    claims.userId()
                            .toString()
            );
        }

        if (claims.organizationId() != null) {
            builder.claim(
                    "organizationId",
                    claims.organizationId()
                            .toString()
            );
        }

        if (claims.tokenVersion() != null) {
            builder.claim(
                    "tokenVersion",
                    claims.tokenVersion()
            );
        }

        if (claims.organizationAuthVersion()
                != null) {

            builder.claim(
                    "organizationAuthVersion",
                    claims.organizationAuthVersion()
            );
        }

        if (claims.roles() != null) {
            builder.claim(
                    "roles",
                    claims.roles()
            );
        }

        return encoder.encode(
                JwtEncoderParameters.from(
                        headers,
                        builder.build()
                )
        ).getTokenValue();
    }

    private static String corruptSignature(
            String token
    ) {
        int signatureSeparator =
                token.lastIndexOf('.');

        if (signatureSeparator < 0
                || signatureSeparator == token.length() - 1) {
            throw new IllegalArgumentException(
                    "JWT не содержит signature segment"
            );
        }

        int signatureStart =
                signatureSeparator + 1;

        char original =
                token.charAt(
                        signatureStart
                );

        char replacement =
                original == 'A'
                        ? 'B'
                        : 'A';

        return token.substring(
                0,
                signatureStart
        ) + replacement
                + token.substring(
                        signatureStart + 1
                );
    }

    private static String bearer(
            String token
    ) {
        return "Bearer " + token;
    }

    private record InvalidSecurityStatusCase(
            String description,
            @Nullable
            UserSecurityStatus securityStatus
    ) {

        private InvalidSecurityStatusCase {
            Objects.requireNonNull(
                    description,
                    "description must not be null"
            );
        }

        @Override
        public @NotNull String toString() {
            return description;
        }
    }

    private record CsrfContext(
            Cookie cookie
    ) {

        private CsrfContext {
            Objects.requireNonNull(
                    cookie,
                    "cookie must not be null"
            );
        }
    }

    private record TokenClaims(
            String issuer,
            List<String> audience,
            Instant issuedAt,
            Instant expiresAt,
            String subject,
            @Nullable UUID userId,
            @Nullable UUID organizationId,
            @Nullable Long tokenVersion,
            @Nullable Long organizationAuthVersion,
            @Nullable List<String> roles
    ) {

        private TokenClaims {
            Objects.requireNonNull(
                    issuer,
                    "issuer must not be null"
            );

            Objects.requireNonNull(
                    audience,
                    "audience must not be null"
            );

            Objects.requireNonNull(
                    issuedAt,
                    "issuedAt must not be null"
            );

            Objects.requireNonNull(
                    expiresAt,
                    "expiresAt must not be null"
            );

            Objects.requireNonNull(
                    subject,
                    "subject must not be null"
            );
        }

        TokenClaims withTimes(
                Instant newIssuedAt,
                Instant newExpiresAt
        ) {
            return new TokenClaims(
                    issuer,
                    audience,
                    newIssuedAt,
                    newExpiresAt,
                    subject,
                    userId,
                    organizationId,
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }

        TokenClaims withWrongIssuer() {
            return new TokenClaims(
                    "https://other-issuer.safeai.test",
                    audience,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    organizationId,
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }

        TokenClaims withAudience(
                List<String> newAudience
        ) {
            return new TokenClaims(
                    issuer,
                    newAudience,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    organizationId,
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }

        TokenClaims withSubject(
                String newSubject
        ) {
            return new TokenClaims(
                    issuer,
                    audience,
                    issuedAt,
                    expiresAt,
                    newSubject,
                    userId,
                    organizationId,
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }

        TokenClaims withRoles(
                List<String> newRoles
        ) {
            return new TokenClaims(
                    issuer,
                    audience,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    organizationId,
                    tokenVersion,
                    organizationAuthVersion,
                    newRoles
            );
        }

        TokenClaims withoutOrganizationId() {
            return new TokenClaims(
                    issuer,
                    audience,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    null,
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }

        TokenClaims withoutOrganizationAuthVersion() {
            return new TokenClaims(
                    issuer,
                    audience,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    organizationId,
                    tokenVersion,
                    null,
                    roles
            );
        }
    }
}