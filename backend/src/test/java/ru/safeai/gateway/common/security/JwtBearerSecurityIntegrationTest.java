package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiErrorResponseWriter;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет полный Bearer authentication pipeline:
 *
 * <pre>
 * RS256 signed JWT
 *      ->
 * JwtDecoder
 *      ->
 * SafeAiJwtAuthenticationConverter
 *      ->
 * Spring Security Resource Server
 *      ->
 * BearerAuthenticationEntryPoint
 *      ->
 * JSON 401 + WWW-Authenticate: Bearer
 * </pre>
 *
 * <p>Generic application 401 и Bearer/resource-server 401 намеренно
 * используют разные {@link org.springframework.security.web.AuthenticationEntryPoint}.</p>
 *
 * <p>Strict SafeAI identity validation выполняется
 * {@link SafeAiJwtAuthenticationConverter}. На resource-server boundary
 * {@link BadJwtException} переводится в
 * {@link InvalidBearerTokenException}, как и в production
 * SecurityConfig.</p>
 */
@WebMvcTest(useDefaultFilters = false)
@Import({
        JwtBearerSecurityIntegrationTest.ProbeController.class,
        JwtBearerSecurityIntegrationTest.TestSecurityConfiguration.class,

        JwtCodecConfiguration.class,
        JwtRsaKeyRing.class,
        SafeAiJwtAuthenticationConverter.class,

        RestAuthenticationEntryPoint.class,
        BearerAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,

        ApiErrorResponseWriter.class,
        ApiErrorResponseFactory.class,
        RequestIdFilter.class
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

    private static final String ISSUER =
            "https://issuer.safeai.test";

    private static final String AUDIENCE =
            "safeai-api";

    private static final String ACTIVE_KEY_ID =
            "test-active-key";

    private static final String INVALID_ACCESS_TOKEN_MESSAGE =
            "Access token is invalid";

    private static final long TOKEN_VERSION =
            7L;

    private static final long ORGANIZATION_AUTH_VERSION =
            11L;

    private static final KeyPair RSA_KEY_PAIR =
            generateRsaKeyPair();

    private static final String PUBLIC_KEY =
            toPublicKeyPem();

    private static final String PRIVATE_KEY =
            toPrivateKeyPem();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @DynamicPropertySource
    static void jwtProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "app.security.jwt.expiration-minutes",
                () -> "15"
        );

        registry.add(
                "app.security.jwt.issuer",
                () -> ISSUER
        );

        registry.add(
                "app.security.jwt.audience",
                () -> AUDIENCE
        );

        registry.add(
                "app.security.jwt.active-key-id",
                () -> ACTIVE_KEY_ID
        );

        registry.add(
                "app.security.jwt.keys[0].id",
                () -> ACTIVE_KEY_ID
        );

        registry.add(
                "app.security.jwt.keys[0].public-key",
                () -> PUBLIC_KEY
        );

        registry.add(
                "app.security.jwt.keys[0].private-key",
                () -> PRIVATE_KEY
        );
    }

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
                RestAuthenticationEntryPoint authenticationEntryPoint,
                BearerAuthenticationEntryPoint bearerAuthenticationEntryPoint,
                RestAccessDeniedHandler accessDeniedHandler
        ) {

            http
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

                    /*
                     * Generic application authentication failures.
                     *
                     * Bearer challenge здесь намеренно не добавляется:
                     * обычный application 401 не должен притворяться
                     * OAuth2 Bearer response.
                     */
                    .exceptionHandling(exceptions ->
                            exceptions
                                    .authenticationEntryPoint(
                                            authenticationEntryPoint
                                    )
                                    .accessDeniedHandler(
                                            accessDeniedHandler
                                    )
                    )

                    /*
                     * Resource Server/Bearer authentication contour.
                     *
                     * SafeAiJwtAuthenticationConverter занимается только
                     * strict SafeAI identity contract.
                     *
                     * Перевод BadJwtException ->
                     * InvalidBearerTokenException выполняется именно
                     * на resource-server boundary.
                     */
                    .oauth2ResourceServer(oauth2 ->
                            oauth2
                                    .authenticationEntryPoint(
                                            bearerAuthenticationEntryPoint
                                    )
                                    .accessDeniedHandler(
                                            accessDeniedHandler
                                    )
                                    .jwt(jwt ->
                                            jwt
                                                    .decoder(
                                                            jwtDecoder
                                                    )
                                                    .jwtAuthenticationConverter(
                                                            resourceServerJwtAuthenticationConverter(
                                                                    converter
                                                            )
                                                    )
                                    )
                    );

            return http.build();
        }

        private static Converter<Jwt, AbstractAuthenticationToken>
        resourceServerJwtAuthenticationConverter(
                SafeAiJwtAuthenticationConverter converter
        ) {
            return jwt -> {
                try {
                    return converter.convert(
                            jwt
                    );
                } catch (BadJwtException exception) {
                    throw new InvalidBearerTokenException(
                            INVALID_ACCESS_TOKEN_MESSAGE,
                            exception
                    );
                }
            };
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
    void multipleRolesReturn401()
            throws Exception {

        TokenClaims claims =
                validClaims()
                        .withRoles(
                                List.of(
                                        "USER",
                                        "ADMIN"
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
                                .value(
                                        401
                                )
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
                NOW.minusSeconds(
                        5L
                ),
                NOW.plusSeconds(
                        300L
                ),
                USER_ID.toString(),
                USER_ID,
                ORGANIZATION_ID,
                TOKEN_VERSION,
                ORGANIZATION_AUTH_VERSION,
                List.of(
                        "USER"
                )
        );
    }

    private String token(
            TokenClaims claims,
            String type
    ) {
        JwsHeader.Builder headerBuilder =
                JwsHeader
                        .with(
                                SignatureAlgorithm.RS256
                        )
                        .keyId(
                                ACTIVE_KEY_ID
                        );

        if (type != null) {
            headerBuilder.type(
                    type
            );
        }

        JwtClaimsSet.Builder claimsBuilder =
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
                                "tokenVersion",
                                claims.tokenVersion()
                        )
                        .claim(
                                "roles",
                                claims.roles()
                        );

        if (claims.organizationAuthVersion()
                != null) {

            claimsBuilder.claim(
                    "organizationAuthVersion",
                    claims.organizationAuthVersion()
            );
        }

        return jwtEncoder
                .encode(
                        JwtEncoderParameters.from(
                                headerBuilder.build(),
                                claimsBuilder.build()
                        )
                )
                .getTokenValue();
    }

    private static String bearer(
            String token
    ) {
        return "Bearer " + token;
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance(
                            "RSA"
                    );

            generator.initialize(
                    2048
            );

            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Не удалось создать RSA key pair для теста",
                    exception
            );
        }
    }

    private static String toPublicKeyPem() {
        return toPem(
                "PUBLIC KEY",
                RSA_KEY_PAIR
                        .getPublic()
                        .getEncoded()
        );
    }

    private static String toPrivateKeyPem() {
        return toPem(
                "PRIVATE KEY",
                RSA_KEY_PAIR
                        .getPrivate()
                        .getEncoded()
        );
    }

    private static String toPem(
            String type,
            byte[] encoded
    ) {
        String body =
                Base64.getMimeEncoder(
                                64,
                                "\n".getBytes(
                                        StandardCharsets.US_ASCII
                                )
                        )
                        .encodeToString(
                                encoded
                        );

        return "-----BEGIN "
                + type
                + "-----\n"
                + body
                + "\n-----END "
                + type
                + "-----";
    }

    private record TokenClaims(
            String issuer,
            List<String> audience,
            Instant issuedAt,
            Instant expiresAt,
            String subject,
            UUID userId,
            UUID organizationId,
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
                    tokenVersion,
                    organizationAuthVersion,
                    roles
            );
        }
    }
}