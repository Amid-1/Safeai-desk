package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiErrorResponseWriter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers =
        SecurityErrorResponseIntegrationTest
                .SecurityProbeController.class
)
@Import({
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

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestReturnsSafeJson401NoStore()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/test/security/authenticated"
                        )
                                .header(
                                        RequestIdFilter
                                                .REQUEST_ID_HEADER,
                                        "client-correlation-1"
                                )
                )
                .andExpect(
                        status().isUnauthorized()
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
                        header().string(
                                RequestIdFilter
                                        .REQUEST_ID_HEADER,
                                not(
                                        "client-correlation-1"
                                )
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
                                        "/test/security/authenticated"
                                )
                )
                .andExpect(
                        jsonPath("$.requestId")
                                .isString()
                )
                .andExpect(
                        jsonPath("$.fieldErrors")
                                .isMap()
                );
    }

    @Test
    void authenticatedButUnauthorizedRequestReturnsSafeJson403NoStore()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/test/security/admin"
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
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                "no-store"
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
                        jsonPath("$.fieldErrors")
                                .isMap()
                );
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping(
                "/test/security/authenticated"
        )
        Map<String, Boolean> authenticated() {
            return Map.of(
                    "ok",
                    true
            );
        }

        @GetMapping(
                "/test/security/admin"
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
                            AbstractHttpConfigurer
                                    ::disable
                    )
                    .requestCache(
                            AbstractHttpConfigurer
                                    ::disable
                    )
                    .sessionManagement(
                            session ->
                                    session
                                            .sessionCreationPolicy(
                                                    SessionCreationPolicy.STATELESS
                                            )
                    )
                    .httpBasic(
                            Customizer.withDefaults()
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
                            auth ->
                                    auth
                                            .requestMatchers(
                                                    "/test/security/admin"
                                            )
                                            .hasRole("ADMIN")
                                            .anyRequest()
                                            .authenticated()
                    )
                    .build();
        }
    }
}
