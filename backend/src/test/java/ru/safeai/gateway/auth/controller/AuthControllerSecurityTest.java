package ru.safeai.gateway.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.auth.dto.AuthUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.dto.LoginResponse;
import ru.safeai.gateway.auth.service.AuthService;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({
        AuthControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class AuthControllerSecurityTest {

    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ORGANIZATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

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
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/auth/login").permitAll()
                            .anyRequest().permitAll()
                    )
                    .build();
        }
    }

    @Test
    void loginEndpointIsPublicAndReturnsToken() throws Exception {
        LoginResponse loginResponse = new LoginResponse(
                "test-jwt-token",
                "Bearer",
                new AuthUserResponse(
                        USER_ID,
                        ORGANIZATION_ID,
                        "admin@test.com",
                        true,
                        Set.of("ADMIN")
                )
        );

        when(authService.login(any(LoginRequest.class)))
                .thenReturn(loginResponse);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "admin@test.com",
                                  "password": "admin123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("test-jwt-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.user.organizationId").value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.user.email").value("admin@test.com"))
                .andExpect(jsonPath("$.user.enabled").value(true))
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"));
    }

    @Test
    void loginWithInvalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meWithoutAuthenticationReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithAuthenticationReturnsCurrentUser() throws Exception {
        SafeAiUserPrincipal principal = currentUser();

        mockMvc.perform(get("/api/auth/me")
                        .with(authentication(authToken(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.organizationId").value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.email").value("admin@test.com"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    private Authentication authToken(SafeAiUserPrincipal principal) {
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
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}