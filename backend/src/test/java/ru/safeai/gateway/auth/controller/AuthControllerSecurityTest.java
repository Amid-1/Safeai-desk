package ru.safeai.gateway.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.security.UserStatusFilter;
import ru.safeai.gateway.auth.service.AuthService;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        AuthControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
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
            Instant.parse("2026-06-12T12:00:00Z");

    /*
     * Соответствует текущей PasswordPolicy:
     * минимум 12 символов, upper/lower case,
     * цифра и специальный символ.
     */
    private static final String VALID_PASSWORD =
            "Admin123!456";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            );
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(
                HttpSecurity http
        ) {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .exceptionHandling(exceptionHandling ->
                            exceptionHandling
                                    .authenticationEntryPoint(
                                            new HttpStatusEntryPoint(
                                                    HttpStatus.UNAUTHORIZED
                                            )
                                    )
                    )
                    .authorizeHttpRequests(authorize ->
                            authorize
                                    .requestMatchers(
                                            "/api/auth/login"
                                    )
                                    .permitAll()
                                    .anyRequest()
                                    .authenticated()
                    )
                    .build();
        }
    }

    @Test
    void loginEndpointIsPublicAndReturnsCurrentUser()
            throws Exception {
        CurrentUserResponse currentUserResponse =
                currentUserResponse();

        when(authService.login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        )).thenReturn(currentUserResponse);

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "email": "admin@test.com",
                                          "password": "%s"
                                        }
                                        """.formatted(VALID_PASSWORD))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(USER_ID.toString()))
                .andExpect(jsonPath("$.organizationId")
                        .value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.email")
                        .value("admin@test.com"))
                .andExpect(jsonPath("$.fullName")
                        .value("Demo Admin"))
                .andExpect(jsonPath("$.enabled")
                        .value(true))
                .andExpect(jsonPath("$.roles[0]")
                        .value("ADMIN"));

        verify(authService).login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );
    }

    @Test
    void loginWithInvalidBodyReturns400()
            throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "email": "",
                                          "password": ""
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verify(authService, never()).login(
                any(LoginRequest.class),
                any(HttpServletRequest.class),
                any(HttpServletResponse.class)
        );
    }

    @Test
    void meWithoutAuthenticationReturns401()
            throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).getCurrentUser(
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void meWithAuthenticationReturnsCurrentUser()
            throws Exception {
        SafeAiUserPrincipal principal =
                currentUser();

        when(authService.getCurrentUser(
                any(SafeAiUserPrincipal.class)
        )).thenReturn(currentUserResponse());

        mockMvc.perform(
                        get("/api/auth/me")
                                .with(authentication(
                                        authToken(principal)
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(USER_ID.toString()))
                .andExpect(jsonPath("$.organizationId")
                        .value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.email")
                        .value("admin@test.com"))
                .andExpect(jsonPath("$.fullName")
                        .value("Demo Admin"))
                .andExpect(jsonPath("$.enabled")
                        .value(true))
                .andExpect(jsonPath("$.roles[0]")
                        .value("ADMIN"));

        verify(authService).getCurrentUser(
                any(SafeAiUserPrincipal.class)
        );
    }

    private CurrentUserResponse currentUserResponse() {
        return new CurrentUserResponse(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "Demo Admin",
                true,
                Set.of("ADMIN")
        );
    }

    private Authentication authToken(
            SafeAiUserPrincipal principal
    ) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    private SafeAiUserPrincipal currentUser() {
        return new SafeAiUserPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "",
                true,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }
}

