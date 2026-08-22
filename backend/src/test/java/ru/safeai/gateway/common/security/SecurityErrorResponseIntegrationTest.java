package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiErrorResponseWriter;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(useDefaultFilters = false)
@Import({
        SecurityErrorResponseIntegrationTest
                .SecurityProbeController.class,

        RequestIdFilter.class,

        ApiErrorResponseFactory.class,
        ApiErrorResponseWriter.class,

        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,

        SecurityErrorResponseIntegrationTest
                .SecurityTestConfiguration.class
})
class SecurityErrorResponseIntegrationTest {

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-12T12:00:00Z"
            );

    private static final String AUTHENTICATED_ENDPOINT =
            "/test/security/authenticated";

    private static final String ADMIN_ENDPOINT =
            "/test/security/admin";

    private static final String CLIENT_REQUEST_ID_401 =
            "client-correlation-1";

    private static final String CLIENT_REQUEST_ID_403 =
            "client-correlation-2";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Generic application authentication failure.
     *
     * <p>Этот flow использует RestAuthenticationEntryPoint,
     * поэтому WWW-Authenticate: Bearer здесь намеренно отсутствует.
     * Bearer challenge принадлежит только OAuth2 Resource Server flow.</p>
     */
    @Test
    void unauthenticatedRequestReturnsSafeJson401NoStoreWithoutBearerChallenge()
            throws Exception {

        MvcResult result =
                mockMvc.perform(
                                get(
                                        AUTHENTICATED_ENDPOINT
                                )
                                        .header(
                                                RequestIdFilter
                                                        .REQUEST_ID_HEADER,
                                                CLIENT_REQUEST_ID_401
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
                                header().doesNotExist(
                                        HttpHeaders.WWW_AUTHENTICATE
                                )
                        )
                        .andExpect(
                                header().exists(
                                        RequestIdFilter
                                                .REQUEST_ID_HEADER
                                )
                        )
                        .andExpect(
                                jsonPath("$.timestamp")
                                        .value(
                                                NOW.toString()
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
                                                AUTHENTICATED_ENDPOINT
                                        )
                        )
                        .andExpect(
                                jsonPath("$.requestId")
                                        .isString()
                        )
                        .andExpect(
                                jsonPath("$.fieldErrors")
                                        .isMap()
                        )
                        .andReturn();

        assertRequestIdContract(
                result,
                CLIENT_REQUEST_ID_401
        );
    }

    /**
     * Authenticated principal without the required role.
     *
     * <p>403 также не должен содержать WWW-Authenticate,
     * потому что authentication уже существует, а отказ связан
     * с authorization.</p>
     */
    @Test
    void authenticatedButUnauthorizedRequestReturnsSafeJson403NoStore()
            throws Exception {

        MvcResult result =
                mockMvc.perform(
                                get(
                                        ADMIN_ENDPOINT
                                )
                                        .header(
                                                RequestIdFilter
                                                        .REQUEST_ID_HEADER,
                                                CLIENT_REQUEST_ID_403
                                        )
                                        .with(
                                                user("user")
                                                        .roles(
                                                                "USER"
                                                        )
                                        )
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
                                header().string(
                                        HttpHeaders.CACHE_CONTROL,
                                        "no-store"
                                )
                        )
                        .andExpect(
                                header().doesNotExist(
                                        HttpHeaders.WWW_AUTHENTICATE
                                )
                        )
                        .andExpect(
                                header().exists(
                                        RequestIdFilter
                                                .REQUEST_ID_HEADER
                                )
                        )
                        .andExpect(
                                jsonPath("$.timestamp")
                                        .value(
                                                NOW.toString()
                                        )
                        )
                        .andExpect(
                                jsonPath("$.status")
                                        .value(403)
                        )
                        .andExpect(
                                jsonPath("$.error")
                                        .value(
                                                "FORBIDDEN"
                                        )
                        )
                        .andExpect(
                                jsonPath("$.message")
                                        .value(
                                                "Доступ запрещён"
                                        )
                        )
                        .andExpect(
                                jsonPath("$.path")
                                        .value(
                                                ADMIN_ENDPOINT
                                        )
                        )
                        .andExpect(
                                jsonPath("$.requestId")
                                        .isString()
                        )
                        .andExpect(
                                jsonPath("$.fieldErrors")
                                        .isMap()
                        )
                        .andReturn();

        assertRequestIdContract(
                result,
                CLIENT_REQUEST_ID_403
        );
    }

    private static void assertRequestIdContract(
            MvcResult result,
            String clientRequestId
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
                        clientRequestId
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
                clientRequestId
        );

        String responseBody =
                new String(
                        result.getResponse()
                                .getContentAsByteArray(),
                        StandardCharsets.UTF_8
                );

        assertThat(
                responseBody
        ).contains(
                "\"requestId\":\""
                        + serverRequestId
                        + "\""
        );
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping(
                AUTHENTICATED_ENDPOINT
        )
        Map<String, Boolean> authenticated() {
            return Map.of(
                    "ok",
                    true
            );
        }

        @GetMapping(
                ADMIN_ENDPOINT
        )
        Map<String, Boolean> admin() {
            return Map.of(
                    "ok",
                    true
            );
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfiguration {

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
                    .sessionManagement(
                            session ->
                                    session
                                            .sessionCreationPolicy(
                                                    SessionCreationPolicy
                                                            .STATELESS
                                            )
                    )
                    .exceptionHandling(
                            exceptions ->
                                    exceptions
                                            .authenticationEntryPoint(
                                                    authenticationEntryPoint
                                            )
                                            .accessDeniedHandler(
                                                    accessDeniedHandler
                                            )
                    )
                    .authorizeHttpRequests(
                            authorization ->
                                    authorization
                                            .requestMatchers(
                                                    ADMIN_ENDPOINT
                                            )
                                            .hasRole(
                                                    "ADMIN"
                                            )
                                            .requestMatchers(
                                                    AUTHENTICATED_ENDPOINT
                                            )
                                            .authenticated()
                                            .anyRequest()
                                            .denyAll()
                    )
                    .build();
        }
    }
}