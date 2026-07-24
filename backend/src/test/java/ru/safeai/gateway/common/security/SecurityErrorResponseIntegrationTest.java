package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers
        .AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning
        .InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiErrorResponseWriter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request
        .MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.status;

@WebMvcTest(useDefaultFilters = false)
@Import({
        SecurityErrorResponseIntegrationTest
                .SecurityProbeController.class,
        ApiErrorResponseWriter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        SecurityErrorResponseIntegrationTest
                .SecurityTestConfiguration.class
})
@TestConstructor(
        autowireMode = TestConstructor.AutowireMode.ALL
)
class SecurityErrorResponseIntegrationTest {

    private static final String ENDPOINT =
            "/test/security/admin";

    private static final String REQUEST_ID =
            "security-request-id";

    private static final Instant FIXED_TIME =
            Instant.parse("2026-06-12T12:00:00Z");

    private final MockMvc mockMvc;

    SecurityErrorResponseIntegrationTest(
            MockMvc mockMvc
    ) {
        this.mockMvc = mockMvc;
    }

    @Test
    void unauthenticatedRequestReturnsCompleteJson401()
            throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .requestAttr(
                                RequestIdFilter
                                        .REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store"
                ))
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer"
                ))
                .andExpect(jsonPath("$.timestamp")
                        .value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.error")
                        .value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message")
                        .value("Требуется авторизация"))
                .andExpect(jsonPath("$.path")
                        .value(ENDPOINT))
                .andExpect(jsonPath("$.requestId")
                        .value(REQUEST_ID))
                .andExpect(jsonPath("$.fieldErrors")
                        .isEmpty());
    }

    @Test
    void invalidCredentialsReturnSameCompleteJson401()
            throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .with(httpBasic(
                                "user",
                                "wrong-password"
                        ))
                        .requestAttr(
                                RequestIdFilter
                                        .REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store"
                ))
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer"
                ))
                .andExpect(jsonPath("$.timestamp")
                        .value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.error")
                        .value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message")
                        .value("Требуется авторизация"))
                .andExpect(jsonPath("$.path")
                        .value(ENDPOINT))
                .andExpect(jsonPath("$.requestId")
                        .value(REQUEST_ID))
                .andExpect(jsonPath("$.fieldErrors")
                        .isEmpty());
    }

    @Test
    void userWithoutRequiredRoleReturnsCompleteJson403()
            throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .with(httpBasic(
                                "user",
                                "password"
                        ))
                        .requestAttr(
                                RequestIdFilter
                                        .REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store"
                ))
                /*
                 * WWW-Authenticate предназначен для 401.
                 * При обычном запрете доступа 403 он не нужен.
                 */
                .andExpect(header().doesNotExist(
                        HttpHeaders.WWW_AUTHENTICATE
                ))
                .andExpect(jsonPath("$.timestamp")
                        .value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.status")
                        .value(403))
                .andExpect(jsonPath("$.error")
                        .value("FORBIDDEN"))
                .andExpect(jsonPath("$.message")
                        .value("Доступ запрещён"))
                .andExpect(jsonPath("$.path")
                        .value(ENDPOINT))
                .andExpect(jsonPath("$.requestId")
                        .value(REQUEST_ID))
                .andExpect(jsonPath("$.fieldErrors")
                        .isEmpty());
    }

    @Test
    void userWithRequiredRoleCanAccessEndpoint()
            throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .with(httpBasic(
                                "admin",
                                "password"
                        ))
                        .requestAttr(
                                RequestIdFilter
                                        .REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"))
                /*
                 * Успешный ответ не должен ошибочно получать
                 * security error headers.
                 */
                .andExpect(header().doesNotExist(
                        HttpHeaders.WWW_AUTHENTICATE
                ));
    }

    @RestController
    @RequestMapping("/test/security")
    static class SecurityProbeController {

        @GetMapping("/admin")
        String admin() {
            return "ok";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfiguration {

        @Bean
        Clock testClock() {
            return Clock.fixed(
                    FIXED_TIME,
                    ZoneOffset.UTC
            );
        }

        @Bean
        ApiErrorResponseFactory apiErrorResponseFactory(
                Clock testClock
        ) {
            return new ApiErrorResponseFactory(
                    testClock
            );
        }

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("user")
                            .password("{noop}password")
                            .roles("USER")
                            .build(),

                    User.withUsername("admin")
                            .password("{noop}password")
                            .roles("ADMIN")
                            .build()
            );
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(
                HttpSecurity http,
                RestAuthenticationEntryPoint
                        authenticationEntryPoint,
                RestAccessDeniedHandler
                        accessDeniedHandler
        ) {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .requestCache(
                            AbstractHttpConfigurer::disable
                    )
                    .authorizeHttpRequests(authorize ->
                            authorize
                                    .requestMatchers(
                                            ENDPOINT
                                    )
                                    .hasRole("ADMIN")
                                    .anyRequest()
                                    .authenticated()
                    )
                    .httpBasic(httpBasic -> httpBasic
                            .authenticationEntryPoint(
                                    authenticationEntryPoint
                            )
                    )
                    .exceptionHandling(exception ->
                            exception
                                    .authenticationEntryPoint(
                                            authenticationEntryPoint
                                    )
                                    .accessDeniedHandler(
                                            accessDeniedHandler
                                    )
                    )
                    .build();
        }
    }
}