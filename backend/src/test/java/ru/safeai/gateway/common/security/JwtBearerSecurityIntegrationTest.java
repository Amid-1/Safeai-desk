package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiErrorResponseWriter;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет полный bearer pipeline.
 * <p>signed JWT -> JwtDecoder -> SafeAiJwtAuthenticationConverter
 * -> Spring Security -> RestAuthenticationEntryPoint -> JSON 401.</p>
 * <p>JwtProperties создаётся только через property binding.
 * Ручного {@code @Bean JwtProperties} здесь быть не должно.</p>
 */
@WebMvcTest(useDefaultFilters = false)
@Import({
        JwtBearerSecurityIntegrationTest.ProbeController.class,
        JwtBearerSecurityIntegrationTest.TestSecurityConfiguration.class,

        JwtCodecConfiguration.class,
        SafeAiJwtAuthenticationConverter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,

        ApiErrorResponseWriter.class,
        ApiErrorResponseFactory.class,
        RequestIdFilter.class
})
@TestPropertySource(properties = {
        "app.security.jwt.secret="
                + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.security.jwt.expiration-minutes=15",
        "app.security.jwt.issuer=https://issuer.safeai.test",
        "app.security.jwt.audience=safeai-api"
})
class JwtBearerSecurityIntegrationTest {

    private static final String ENDPOINT =
            "/api/test/bearer";

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-12T12:00:00Z"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID OTHER_USER_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final String EMAIL =
            "user@test.com";

    private static final String ISSUER =
            "https://issuer.safeai.test";

    private static final String AUDIENCE =
            "safeai-api";

    private static final long TOKEN_VERSION =
            7L;

    private static final long ORGANIZATION_AUTH_VERSION =
            11L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @RestController
    static class ProbeController {

        @GetMapping(ENDPOINT)
        String probe() {
            return "ok";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
            JwtProperties.class
    )
    static class TestSecurityConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            );
        }

        @Bean
        SecurityFilterChain securityFilterChain(
                HttpSecurity http,
                JwtDecoder jwtDecoder,
                SafeAiJwtAuthenticationConverter converter,
                RestAuthenticationEntryPoint
                        authenticationEntryPoint,
                RestAccessDeniedHandler
                        accessDeniedHandler
        ) {

            return http
                    .csrf(
                            AbstractHttpConfigurer::disable
                    )
                    .requestCache(
                            AbstractHttpConfigurer::disable
                    )
                    .sessionManagement(session ->
                            session.sessionCreationPolicy(
                                    SessionCreationPolicy.STATELESS
                            )
                    )
                    .authorizeHttpRequests(authorize ->
                            authorize
                                    .anyRequest()
                                    .authenticated()
                    )
                    .exceptionHandling(exceptions ->
                            exceptions
                                    .authenticationEntryPoint(
                                            authenticationEntryPoint
                                    )
                                    .accessDeniedHandler(
                                            accessDeniedHandler
                                    )
                    )
                    .oauth2ResourceServer(oauth2 ->
                            oauth2
                                    .authenticationEntryPoint(
                                            authenticationEntryPoint
                                    )
                                    .jwt(jwt ->
                                            jwt
                                                    .decoder(
                                                            jwtDecoder
                                                    )
                                                    .jwtAuthenticationConverter(
                                                            converter
                                                                    ::convertForResourceServer
                                                    )
                                    )
                    )
                    .build();
        }
    }

    @Test
    void validJwtIsAccepted()
            throws Exception {

        mockMvc.perform(
                        get(ENDPOINT)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token(
                                                        validClaims(),
                                                        "JWT"
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        content().string(
                                "ok"
                        )
                );
    }

    @Test
    void missingOrganizationAuthVersionReturns401()
            throws Exception {

        TokenClaims claims =
                validClaims()
                        .withOrganizationAuthVersion(
                                null
                        );

        assertRejected(
                token(
                        claims,
                        "JWT"
                )
        );
    }

    @Test
    void negativeOrganizationAuthVersionReturns401()
            throws Exception {

        TokenClaims claims =
                validClaims()
                        .withOrganizationAuthVersion(
                                -1L
                        );

        assertRejected(
                token(
                        claims,
                        "JWT"
                )
        );
    }

    @Test
    void fractionalOrganizationAuthVersionReturns401()
            throws Exception {

        TokenClaims claims =
                validClaims()
                        .withOrganizationAuthVersion(
                                1.5D
                        );

        assertRejected(
                token(
                        claims,
                        "JWT"
                )
        );
    }

    @Test
    void overflowingOrganizationAuthVersionReturns401()
            throws Exception {

        BigInteger overflow =
                BigInteger.valueOf(
                                Long.MAX_VALUE
                        )
                        .add(
                                BigInteger.ONE
                        );

        TokenClaims claims =
                validClaims()
                        .withOrganizationAuthVersion(
                                overflow
                        );

        assertRejected(
                token(
                        claims,
                        "JWT"
                )
        );
    }

    @Test
    void subjectDifferentFromUserIdReturns401()
            throws Exception {

        TokenClaims claims =
                validClaims()
                        .withSubject(
                                OTHER_USER_ID.toString()
                        );

        assertRejected(
                token(
                        claims,
                        "JWT"
                )
        );
    }

    @Test
    void unknownRoleReturns401()
            throws Exception {

        TokenClaims claims =
                validClaims()
                        .withRoles(
                                List.of(
                                        "ROOT"
                                )
                        );

        assertRejected(
                token(
                        claims,
                        "JWT"
                )
        );
    }

    @Test
    void wrongIssuerReturns401()
            throws Exception {

        TokenClaims claims =
                validClaims()
                        .withWrongIssuer();

        assertRejected(
                token(
                        claims,
                        "JWT"
                )
        );
    }

    @Test
    void wrongAudienceReturns401()
            throws Exception {

        TokenClaims claims =
                validClaims()
                        .withAudience(
                                List.of(
                                        "other-api"
                                )
                        );

        assertRejected(
                token(
                        claims,
                        "JWT"
                )
        );
    }

    @Test
    void missingTypeReturns401()
            throws Exception {

        assertRejected(
                token(
                        validClaims(),
                        null
                )
        );
    }

    @Test
    void unexpectedTypeReturns401()
            throws Exception {

        assertRejected(
                token(
                        validClaims(),
                        "at+jwt"
                )
        );
    }

    private void assertRejected(
            String rawToken
    ) throws Exception {

        mockMvc.perform(
                        get(ENDPOINT)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                rawToken
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
                        header().string(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer"
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
                        jsonPath("$.message")
                                .value(
                                        "Требуется авторизация"
                                )
                )
                .andExpect(
                        jsonPath("$.path")
                                .value(
                                        ENDPOINT
                                )
                )
                .andExpect(
                        jsonPath("$.fieldErrors")
                                .isMap()
                );
    }

    private TokenClaims validClaims() {
        return new TokenClaims(
                ISSUER,
                List.of(
                        AUDIENCE
                ),
                NOW.minusSeconds(5),
                NOW.plusSeconds(300),
                USER_ID.toString(),
                USER_ID,
                ORGANIZATION_ID,
                EMAIL,
                TOKEN_VERSION,
                ORGANIZATION_AUTH_VERSION,
                List.of("USER")
        );
    }

    private String token(
            TokenClaims claims,
            String type
    ) {
        JwsHeader headers =
                type == null
                        ? JwsHeader
                        .with(
                                MacAlgorithm.HS256
                        )
                        .build()
                        : JwsHeader
                        .with(
                                MacAlgorithm.HS256
                        )
                        .type(type)
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
                        )
                        .claim(
                                "userId",
                                claims.userId()
                                        .toString()
                        )
                        .claim(
                                "organizationId",
                                claims.organizationId()
                                        .toString()
                        )
                        .claim(
                                "email",
                                claims.email()
                        )
                        .claim(
                                "tokenVersion",
                                claims.tokenVersion()
                        )
                        .claim(
                                "roles",
                                claims.roles()
                        );

        if (claims.organizationAuthVersion()
                != null) {

            builder.claim(
                    "organizationAuthVersion",
                    claims.organizationAuthVersion()
            );
        }

        return jwtEncoder
                .encode(
                        JwtEncoderParameters.from(
                                headers,
                                builder.build()
                        )
                )
                .getTokenValue();
    }

    private static String bearer(
            String token
    ) {
        return "Bearer " + token;
    }

    private record TokenClaims(
            String issuer,
            List<String> audience,
            Instant issuedAt,
            Instant expiresAt,
            String subject,
            UUID userId,
            UUID organizationId,
            String email,
            long tokenVersion,
            Object organizationAuthVersion,
            List<String> roles
    ) {

        TokenClaims withOrganizationAuthVersion(
                Object value
        ) {
            return new TokenClaims(
                    issuer,
                    audience,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    organizationId,
                    email,
                    tokenVersion,
                    value,
                    roles
            );
        }

        TokenClaims withSubject(
                String value
        ) {
            return new TokenClaims(
                    issuer,
                    audience,
                    issuedAt,
                    expiresAt,
                    value,
                    userId,
                    organizationId,
                    email,
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }

        TokenClaims withRoles(
                List<String> value
        ) {
            return new TokenClaims(
                    issuer,
                    audience,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    organizationId,
                    email,
                    tokenVersion,
                    organizationAuthVersion,
                    value
            );
        }

        TokenClaims withWrongIssuer() {
            return new TokenClaims(
                    "https://other.safeai.test",
                    audience,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    organizationId,
                    email,
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }

        TokenClaims withAudience(
                List<String> value
        ) {
            return new TokenClaims(
                    issuer,
                    value,
                    issuedAt,
                    expiresAt,
                    subject,
                    userId,
                    organizationId,
                    email,
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }
    }
}
