package ru.safeai.gateway.user.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
import ru.safeai.gateway.auth.security.UserStatusFilter;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.UserDetailsResponse;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.dto.UserStatisticsResponse;
import ru.safeai.gateway.user.service.UserService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = UserController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        UserControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
class UserControllerSecurityTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final long USER_VERSION = 7L;

    private static final Instant CREATED_AT =
            Instant.parse("2026-06-12T12:00:00Z");

    private static final Instant UPDATED_AT =
            Instant.parse("2026-06-13T12:00:00Z");

    private static final Instant LAST_LOGIN_AT =
            Instant.parse("2026-06-14T12:00:00Z");

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
        Clock clock() {
            return Clock.fixed(
                    CREATED_AT,
                    ZoneOffset.UTC
            );
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(
                HttpSecurity http
        ) {
            return http
                    .csrf(
                            AbstractHttpConfigurer::disable
                    )
                    .authorizeHttpRequests(auth -> auth
                            .anyRequest()
                            .authenticated()
                    )
                    .build();
        }
    }

    @Test
    void findAllWithoutAuthenticationReturns4xx()
            throws Exception {
        mockMvc.perform(
                get("/api/users")
        ).andExpect(
                status().is4xxClientError()
        );
    }

    @Test
    void findAllWithUserRoleReturns403()
            throws Exception {
        mockMvc.perform(
                get("/api/users")
                        .with(authentication(
                                authToken(
                                        userPrincipal()
                                )
                        ))
        ).andExpect(
                status().isForbidden()
        );

        verify(
                userService,
                never()
        ).findAll(
                any(SafeAiUserPrincipal.class),
                isNull(),
                any(Pageable.class)
        );
    }

    @Test
    void findAllWithAdminRoleReturns200()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                adminPrincipal();

        when(userService.findAll(
                any(SafeAiUserPrincipal.class),
                isNull(),
                any(Pageable.class)
        )).thenReturn(
                new PageImpl<>(
                        List.of(userResponse())
                )
        );

        mockMvc.perform(
                get("/api/users")
                        .with(authentication(
                                authToken(currentUser)
                        ))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.content[0].id"
                ).value(USER_ID.toString()))
                .andExpect(jsonPath(
                        "$.content[0].email"
                ).value("user@test.com"))
                .andExpect(jsonPath(
                        "$.content[0].version"
                ).value(USER_VERSION))
                .andExpect(jsonPath(
                        "$.content[0].updatedAt"
                ).value(UPDATED_AT.toString()))
                .andExpect(jsonPath(
                        "$.content[0].lastLoginAt"
                ).value(LAST_LOGIN_AT.toString()));

        verify(userService).findAll(
                any(SafeAiUserPrincipal.class),
                isNull(),
                any(Pageable.class)
        );
    }

    @Test
    void findAllPassesRoleFilterToService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                adminPrincipal();

        when(userService.findAll(
                any(SafeAiUserPrincipal.class),
                eq("USER"),
                any(Pageable.class)
        )).thenReturn(
                new PageImpl<>(
                        List.of(userResponse())
                )
        );

        mockMvc.perform(
                get("/api/users")
                        .param("role", "USER")
                        .with(authentication(
                                authToken(currentUser)
                        ))
        ).andExpect(
                status().isOk()
        );

        verify(userService).findAll(
                any(SafeAiUserPrincipal.class),
                eq("USER"),
                any(Pageable.class)
        );
    }

    @Test
    void statisticsWithAdminRoleReturnsGlobalVisibleCounts()
            throws Exception {
        when(userService.statistics(
                any(SafeAiUserPrincipal.class)
        )).thenReturn(
                new UserStatisticsResponse(
                        10,
                        2,
                        8,
                        9,
                        1
                )
        );

        mockMvc.perform(
                get("/api/users/statistics")
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.total").value(10)
                )
                .andExpect(
                        jsonPath("$.administrators").value(2)
                )
                .andExpect(
                        jsonPath("$.users").value(8)
                )
                .andExpect(
                        jsonPath("$.enabled").value(9)
                )
                .andExpect(
                        jsonPath("$.disabled").value(1)
                );
    }

    @Test
    void detailsWithAdminRoleReturnsVersionedContract()
            throws Exception {
        when(userService.findDetailsById(
                eq(USER_ID),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(
                userDetailsResponse()
        );

        mockMvc.perform(
                get("/api/users/{id}", USER_ID)
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(USER_ID.toString())
                )
                .andExpect(
                        jsonPath("$.organizationId")
                                .value(
                                        ORGANIZATION_ID.toString()
                                )
                )
                .andExpect(
                        jsonPath("$.version")
                                .value(USER_VERSION)
                )
                .andExpect(
                        jsonPath("$.createdAt")
                                .value(CREATED_AT.toString())
                )
                .andExpect(
                        jsonPath("$.updatedAt")
                                .value(UPDATED_AT.toString())
                )
                .andExpect(
                        jsonPath("$.lastLoginAt")
                                .value(LAST_LOGIN_AT.toString())
                );
    }

    @Test
    void createWithAdminRoleReturns201()
            throws Exception {
        when(userService.create(
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(userResponse());

        mockMvc.perform(
                post("/api/users")
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId": "%s",
                                  "email": "user@test.com",
                                  "password": "Strong_User_123!",
                                  "fullName": "Demo User",
                                  "roles": ["USER"]
                                }
                                """.formatted(ORGANIZATION_ID))
        )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(USER_ID.toString())
                )
                .andExpect(
                        jsonPath("$.version")
                                .value(USER_VERSION)
                );

        verify(userService).create(
                any(),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void createWithWeakPasswordReturns400AndDoesNotCallService()
            throws Exception {
        mockMvc.perform(
                post("/api/users")
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "organizationId": "%s",
                                  "email": "user@test.com",
                                  "password": "weak",
                                  "roles": ["USER"]
                                }
                                """.formatted(
                                ORGANIZATION_ID
                        ))
        ).andExpect(
                status().isBadRequest()
        );

        verify(userService, never())
                .create(any(), any());
    }

    @Test
    void updateUserWithAdminRoleReturns200()
            throws Exception {
        when(userService.updateUser(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(userResponse());

        mockMvc.perform(
                patch("/api/users/{id}", USER_ID)
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@test.com",
                                  "fullName": "Demo User",
                                  "expectedVersion": %d
                                }
                                """.formatted(USER_VERSION))
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(USER_ID.toString())
                );

        verify(userService).updateUser(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void updateRolesWithAdminRoleReturns200()
            throws Exception {
        when(userService.updateRoles(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(userResponse());

        mockMvc.perform(
                patch("/api/users/{id}/roles", USER_ID)
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roles": ["USER"],
                                  "expectedVersion": %d
                                }
                                """.formatted(USER_VERSION))
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.roles[0]")
                                .value("USER")
                );

        verify(userService).updateRoles(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void updateEnabledWithAdminRoleReturns200()
            throws Exception {
        when(userService.updateEnabled(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(
                disabledUserResponse()
        );

        mockMvc.perform(
                patch(
                        "/api/users/{id}/enabled",
                        USER_ID
                )
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "enabled": false,
                                  "expectedVersion": %d
                                }
                                """.formatted(
                                USER_VERSION
                        ))
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.enabled")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$.version")
                                .value(USER_VERSION + 1)
                );
    }

    @Test
    void resetPasswordWithAdminRoleReturns204()
            throws Exception {
        mockMvc.perform(
                post(
                        "/api/users/{id}/reset-password",
                        USER_ID
                )
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "password": "NewPass123!45",
                                  "expectedVersion": %d
                                }
                                """.formatted(
                                USER_VERSION
                        ))
        ).andExpect(
                status().isNoContent()
        );

        verify(userService).resetPassword(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void permanentDeletionWithAdminRoleReturns403()
            throws Exception {
        mockMvc.perform(
                post(
                        "/api/users/{id}/permanent-deletion",
                        USER_ID
                )
                        .with(authentication(
                                authToken(adminPrincipal())
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "confirmationEmail": "user@test.com",
                                  "expectedVersion": %d
                                }
                                """.formatted(
                                USER_VERSION
                        ))
        ).andExpect(
                status().isForbidden()
        );

        verify(userService, never())
                .permanentlyDelete(
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    void permanentDeletionWithSuperAdminReturns204()
            throws Exception {
        mockMvc.perform(
                post(
                        "/api/users/{id}/permanent-deletion",
                        USER_ID
                )
                        .with(authentication(
                                authToken(superAdminPrincipal())
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "confirmationEmail": "user@test.com",
                                  "expectedVersion": %d
                                }
                                """.formatted(
                                USER_VERSION
                        ))
        ).andExpect(
                status().isNoContent()
        );

        verify(userService).permanentlyDelete(
                eq(USER_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void permanentDeletionWithInvalidEmailReturns400()
            throws Exception {
        mockMvc.perform(
                post(
                        "/api/users/{id}/permanent-deletion",
                        USER_ID
                )
                        .with(authentication(
                                authToken(superAdminPrincipal())
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "confirmationEmail": "not-an-email",
                                  "expectedVersion": %d
                                }
                                """.formatted(
                                USER_VERSION
                        ))
        ).andExpect(
                status().isBadRequest()
        );

        verify(userService, never())
                .permanentlyDelete(
                        any(),
                        any(),
                        any()
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

    private SafeAiUserPrincipal userPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_USER"
                                )
                        )
                );
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        ADMIN_ID,
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

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        SUPER_ADMIN_ID,
                        PLATFORM_ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }

    private UserResponse userResponse() {
        return new UserResponse(
                USER_ID,
                ORGANIZATION_ID,
                "user@test.com",
                "Demo User",
                true,
                Set.of("USER"),
                USER_VERSION,
                CREATED_AT,
                UPDATED_AT,
                LAST_LOGIN_AT
        );
    }

    private UserResponse disabledUserResponse() {
        return new UserResponse(
                USER_ID,
                ORGANIZATION_ID,
                "user@test.com",
                "Demo User",
                false,
                Set.of("USER"),
                USER_VERSION + 1,
                CREATED_AT,
                UPDATED_AT,
                LAST_LOGIN_AT
        );
    }

    private UserDetailsResponse userDetailsResponse() {
        return new UserDetailsResponse(
                USER_ID,
                ORGANIZATION_ID,
                "user@test.com",
                "Demo User",
                true,
                Set.of("USER"),
                USER_VERSION,
                CREATED_AT,
                UPDATED_AT,
                LAST_LOGIN_AT
        );
    }
}
