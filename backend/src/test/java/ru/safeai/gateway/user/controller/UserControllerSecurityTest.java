package ru.safeai.gateway.user.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.UserStatusFilter;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.service.UserService;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = UserController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        UserControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class UserControllerSecurityTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ADMIN_ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth
                            .anyRequest().authenticated()
                    )
                    .build();
        }
    }

    @Test
    void findAllWithoutAuthenticationReturns4xx() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void findAllWithUserRoleReturns403() throws Exception {
        mockMvc.perform(get("/api/users")
                        .with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void findAllWithAdminRoleReturns200() throws Exception {
        SafeAiUserPrincipal currentUser = adminPrincipal();

        when(userService.findAll(any(SafeAiUserPrincipal.class)))
                .thenReturn(List.of(adminResponse()));

        mockMvc.perform(get("/api/users")
                        .with(authentication(authToken(currentUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(USER_ID.toString()))
                .andExpect(jsonPath("$[0].organizationId").value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$[0].email").value("admin@test.com"))
                .andExpect(jsonPath("$[0].fullName").value("Demo Admin"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].roles[0]").value("ADMIN"));
    }

    @Test
    void updateEnabledWithUserRoleReturns403() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/enabled", USER_ID)
                        .with(user("user@test.com").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateEnabledWithAdminRoleReturns200() throws Exception {
        SafeAiUserPrincipal currentUser = adminPrincipal();

        when(userService.updateEnabled(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(disabledUserResponse());

        mockMvc.perform(patch("/api/users/{id}/enabled", USER_ID)
                        .with(authentication(authToken(currentUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void updateRolesWithAdminRoleReturns200() throws Exception {
        SafeAiUserPrincipal currentUser = adminPrincipal();

        when(userService.updateRoles(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(adminResponse());

        mockMvc.perform(patch("/api/users/{id}/roles", USER_ID)
                        .with(authentication(authToken(currentUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roles": ["ADMIN"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void resetPasswordWithAdminRoleReturns200() throws Exception {
        SafeAiUserPrincipal currentUser = adminPrincipal();

        when(userService.resetPassword(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(adminResponse());

        mockMvc.perform(post("/api/users/{id}/reset-password", USER_ID)
                        .with(authentication(authToken(currentUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "password": "NewPass123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()));
    }

    private Authentication authToken(SafeAiUserPrincipal currentUser) {
        return new UsernamePasswordAuthenticationToken(
                currentUser,
                null,
                currentUser.getAuthorities()
        );
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return new SafeAiUserPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "encoded-password",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private UserResponse adminResponse() {
        return new UserResponse(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "Demo Admin",
                true,
                Set.of("ADMIN"),
                Instant.parse("2026-05-31T12:00:00Z")
        );
    }

    private UserResponse disabledUserResponse() {
        return new UserResponse(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "Demo Admin",
                false,
                Set.of("ADMIN"),
                Instant.parse("2026-05-31T12:00:00Z")
        );
    }
}