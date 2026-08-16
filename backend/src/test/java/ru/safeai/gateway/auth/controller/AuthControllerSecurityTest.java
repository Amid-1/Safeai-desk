package ru.safeai.gateway.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.service.AuthService;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(useDefaultFilters = false)
@Import({
        AuthController.class,
        AuthControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class
})
class AuthControllerSecurityTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final Instant NOW =
            Instant.parse("2026-07-23T12:00:00Z");

    private static final String EMAIL =
            "admin@test.com";

    private static final String VALID_PASSWORD =
            "Admin123!456";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        Clock testClock() {
            return Clock.fixed(
                    NOW,
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
        SecurityFilterChain testSecurityFilterChain(
                HttpSecurity http
        ) {
            return http
                    .csrf(Customizer.withDefaults())
                    .exceptionHandling(exceptions ->
                            exceptions.authenticationEntryPoint(
                                    new HttpStatusEntryPoint(
                                            HttpStatus.UNAUTHORIZED
                                    )
                            )
                    )
                    .authorizeHttpRequests(authorize ->
                            authorize
                                    .requestMatchers(
                                            HttpMethod.POST,
                                            "/api/auth/login",
                                            "/api/auth/refresh",
                                            "/api/auth/logout"
                                    )
                                    .permitAll()
                                    .requestMatchers(
                                            HttpMethod.GET,
                                            "/api/auth/me"
                                    )
                                    .authenticated()
                                    .anyRequest()
                                    .denyAll()
                    )
                    .build();
        }
    }

    @Test
    void loginWithoutCsrfReturns403AndDoesNotCallService()
            throws Exception {
        mockMvc.perform(
                        validLoginRequest()
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(authService);
    }

    @Test
    void loginWithValidBodyReturnsCurrentUserAndBindsDto()
            throws Exception {
        when(authService.login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        )).thenReturn(currentUserResponse());

        mockMvc.perform(
                        validLoginRequest()
                                .with(csrf().asHeader())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(USER_ID.toString()))
                .andExpect(jsonPath("$.organizationId")
                        .value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.email")
                        .value(EMAIL))
                .andExpect(jsonPath("$.fullName")
                        .value("Demo Admin"))
                .andExpect(jsonPath("$.enabled")
                        .value(true))
                .andExpect(jsonPath("$.roles")
                        .value(contains("ADMIN")));

        ArgumentCaptor<LoginRequest> requestCaptor =
                ArgumentCaptor.forClass(
                        LoginRequest.class
                );

        verify(authService).login(
                requestCaptor.capture(),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        LoginRequest capturedRequest =
                requestCaptor.getValue();

        assertThat(capturedRequest.email())
                .isEqualTo(EMAIL);

        assertThat(capturedRequest.password())
                .isEqualTo(VALID_PASSWORD);

        verifyNoMoreInteractions(authService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLoginBodies")
    void invalidLoginBodyReturns400AndDoesNotCallService(
            String description,
            String requestBody,
            String invalidField
    ) throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf().asHeader())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400))
                .andExpect(jsonPath("$.error")
                        .value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/login"))
                .andExpect(jsonPath(
                        "$.fieldErrors." + invalidField
                ).isArray());

        verifyNoInteractions(authService);
    }

    @Test
    void loginWithMalformedJsonReturns400AndDoesNotCallService()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf().asHeader())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "email": "admin@test.com",
                                          "password":
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400))
                .andExpect(jsonPath("$.error")
                        .value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/login"));

        verifyNoInteractions(authService);
    }

    @Test
    void loginWithoutBodyReturns400AndDoesNotCallService()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf().asHeader())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400))
                .andExpect(jsonPath("$.error")
                        .value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/login"));

        verifyNoInteractions(authService);
    }

    @Test
    void loginWithUnsupportedMediaTypeReturns415AndDoesNotCallService()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf().asHeader())
                                .contentType(
                                        MediaType.TEXT_PLAIN
                                )
                                .content("not-json")
                )
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(authService);
    }

    @Test
    void loginWhenCredentialsAreInvalidReturns401()
            throws Exception {
        when(authService.login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        )).thenThrow(
                new BadCredentialsException(
                        "Bad credentials"
                )
        );

        mockMvc.perform(
                        validLoginRequest()
                                .with(csrf().asHeader())
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/login"));

        verify(authService).login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void loginWhenRateLimitExceededReturns429AndRetryAfter()
            throws Exception {
        when(authService.login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        )).thenThrow(
                new RateLimitExceededException(
                        "Слишком много попыток входа",
                        Duration.ofSeconds(60)
                )
        );

        mockMvc.perform(
                        validLoginRequest()
                                .with(csrf().asHeader())
                )
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(
                        HttpHeaders.RETRY_AFTER,
                        "60"
                ))
                .andExpect(jsonPath("$.status")
                        .value(429))
                .andExpect(jsonPath("$.error")
                        .value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/login"));

        verify(authService).login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void loginWhenRateLimitServiceUnavailableReturns503()
            throws Exception {
        when(authService.login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        )).thenThrow(
                new RateLimitUnavailableException(
                        "Redis unavailable",
                        new IllegalStateException(
                                "redis.internal:6379"
                        )
                )
        );

        mockMvc.perform(
                        validLoginRequest()
                                .with(csrf().asHeader())
                )
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status")
                        .value(503))
                .andExpect(jsonPath("$.error")
                        .value("RATE_LIMIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/login"));

        verify(authService).login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void refreshWithoutCsrfReturns403AndDoesNotCallService()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/refresh")
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(authService);
    }

    @Test
    void refreshWithCsrfReturns204AndCallsService()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/refresh")
                                .with(csrf().asHeader())
                )
                .andExpect(status().isNoContent());

        verify(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void refreshWithInvalidTokenReturns401()
            throws Exception {
        doThrow(
                new InvalidRefreshTokenException(
                        "Refresh token не найден"
                )
        ).when(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .with(csrf().asHeader())
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.error")
                        .value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/refresh"));

        verify(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void refreshWithExpiredTokenReturns401()
            throws Exception {
        doThrow(
                new ExpiredRefreshTokenException(
                        "Refresh token истёк"
                )
        ).when(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .with(csrf().asHeader())
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.error")
                        .value("EXPIRED_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/refresh"));

        verify(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void refreshWithReusedTokenReturnsGeneric401()
            throws Exception {
        doThrow(
                new RefreshTokenReuseDetectedException(
                        "Refresh token reuse detected",
                        USER_ID,
                        ORGANIZATION_ID,
                        UUID.fromString(
                                "cccccccc-cccc-cccc-cccc-cccccccccccc"
                        )
                )
        ).when(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .with(csrf().asHeader())
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.error")
                        .value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/refresh"));

        verify(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void refreshWhenUnexpectedFailureReturnsSafe500()
            throws Exception {
        doThrow(
                new IllegalStateException(
                        "database password leaked"
                )
        ).when(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        mockMvc.perform(
                        post("/api/auth/refresh")
                                .with(csrf().asHeader())
                )
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status")
                        .value(500))
                .andExpect(jsonPath("$.error")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("Внутренняя ошибка сервера"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/refresh"));

        verify(authService).refresh(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void logoutWithoutCsrfReturns403AndDoesNotCallService()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/logout")
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(authService);
    }

    @Test
    void logoutWithCsrfReturns204AndCallsService()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/logout")
                                .with(csrf().asHeader())
                )
                .andExpect(status().isNoContent());

        verify(authService).logout(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void logoutWhenUnexpectedFailureReturnsSafe500()
            throws Exception {
        doThrow(
                new IllegalStateException(
                        "database password leaked"
                )
        ).when(authService).logout(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        mockMvc.perform(
                        post("/api/auth/logout")
                                .with(csrf().asHeader())
                )
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status")
                        .value(500))
                .andExpect(jsonPath("$.error")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("Внутренняя ошибка сервера"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/logout"));

        verify(authService).logout(
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void meWithoutAuthenticationReturns401AndDoesNotCallService()
            throws Exception {
        mockMvc.perform(
                        get("/api/auth/me")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
    }

    @Test
    void meWithAuthenticationReturnsCurrentUser()
            throws Exception {
        SafeAiUserPrincipal principal =
                currentPrincipal();

        when(authService.getCurrentUser(principal))
                .thenReturn(currentUserResponse());

        mockMvc.perform(
                        get("/api/auth/me")
                                .with(authentication(
                                        authenticationToken(
                                                principal
                                        )
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(USER_ID.toString()))
                .andExpect(jsonPath("$.organizationId")
                        .value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.email")
                        .value(EMAIL))
                .andExpect(jsonPath("$.fullName")
                        .value("Demo Admin"))
                .andExpect(jsonPath("$.enabled")
                        .value(true))
                .andExpect(jsonPath("$.roles")
                        .value(contains("ADMIN")));

        verify(authService).getCurrentUser(
                principal
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void meWithNullFullNameReturnsCurrentUserWithoutSerializationFailure()
            throws Exception {
        SafeAiUserPrincipal principal =
                currentPrincipal();

        when(authService.getCurrentUser(principal))
                .thenReturn(
                        new CurrentUserResponse(
                                USER_ID,
                                ORGANIZATION_ID,
                                EMAIL,
                                null,
                                true,
                                Set.of("ADMIN")
                        )
                );

        mockMvc.perform(
                        get("/api/auth/me")
                                .with(authentication(
                                        authenticationToken(
                                                principal
                                        )
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(USER_ID.toString()))
                .andExpect(jsonPath("$.organizationId")
                        .value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.email")
                        .value(EMAIL))
                .andExpect(jsonPath("$.enabled")
                        .value(true))
                .andExpect(jsonPath("$.roles")
                        .value(contains("ADMIN")));

        verify(authService).getCurrentUser(
                principal
        );

        verifyNoMoreInteractions(authService);
    }

    @Test
    void meWhenServiceFailsReturnsSafe500()
            throws Exception {
        SafeAiUserPrincipal principal =
                currentPrincipal();

        when(authService.getCurrentUser(principal))
                .thenThrow(
                        new IllegalStateException(
                                "database password leaked"
                        )
                );

        mockMvc.perform(
                        get("/api/auth/me")
                                .with(authentication(
                                        authenticationToken(
                                                principal
                                        )
                                ))
                )
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status")
                        .value(500))
                .andExpect(jsonPath("$.error")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("Внутренняя ошибка сервера"))
                .andExpect(jsonPath("$.path")
                        .value("/api/auth/me"));

        verify(authService).getCurrentUser(
                principal
        );

        verifyNoMoreInteractions(authService);
    }

    private static Stream<Arguments> invalidLoginBodies() {
        return Stream.of(
                Arguments.of(
                        "blank email",
                        loginJson(
                                "",
                                VALID_PASSWORD
                        ),
                        "email"
                ),
                Arguments.of(
                        "invalid email format",
                        loginJson(
                                "not-an-email",
                                VALID_PASSWORD
                        ),
                        "email"
                ),
                Arguments.of(
                        "email longer than 255 characters",
                        loginJson(
                                "a".repeat(244)
                                        + "@example.com",
                                VALID_PASSWORD
                        ),
                        "email"
                ),
                Arguments.of(
                        "null email",
                        loginJson(
                                null,
                                VALID_PASSWORD
                        ),
                        "email"
                ),
                Arguments.of(
                        "blank password",
                        loginJson(
                                EMAIL,
                                " "
                        ),
                        "password"
                ),
                Arguments.of(
                        "password longer than 100 characters",
                        loginJson(
                                EMAIL,
                                "p".repeat(101)
                        ),
                        "password"
                ),
                Arguments.of(
                        "null password",
                        loginJson(
                                EMAIL,
                                null
                        ),
                        "password"
                )
        );
    }

    private static String loginJson(
            String email,
            String password
    ) {
        return """
                {
                  "email": %s,
                  "password": %s
                }
                """.formatted(
                jsonValue(email),
                jsonValue(password)
        );
    }

    private static String jsonValue(
            String value
    ) {
        if (value == null) {
            return "null";
        }

        return "\""
                + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                + "\"";
    }

    private MockHttpServletRequestBuilder validLoginRequest() {
        return post("/api/auth/login")
                .contentType(
                        MediaType.APPLICATION_JSON
                )
                .content(
                        loginJson(
                                EMAIL,
                                VALID_PASSWORD
                        )
                );
    }

    private CurrentUserResponse currentUserResponse() {
        return new CurrentUserResponse(
                USER_ID,
                ORGANIZATION_ID,
                EMAIL,
                "Demo Admin",
                true,
                Set.of("ADMIN")
        );
    }

    private Authentication authenticationToken(
            SafeAiUserPrincipal principal
    ) {
        return UsernamePasswordAuthenticationToken
                .authenticated(
                        principal,
                        null,
                        principal.getAuthorities()
                );
    }

    private SafeAiUserPrincipal currentPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );
    }
}