package ru.safeai.gateway.organization.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import ru.safeai.gateway.common.exception.OrganizationVersionConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.DisableOrganizationRequest;
import ru.safeai.gateway.organization.dto.EnableOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationDirectoryResponse;
import ru.safeai.gateway.organization.dto.OrganizationDisableImpactResponse;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.OrganizationType;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
import ru.safeai.gateway.organization.service.OrganizationService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
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
        controllers = OrganizationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        OrganizationControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
class OrganizationControllerSecurityTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-06-12T12:00:00Z"
            );

    private static final long VERSION = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
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
                    .csrf(
                            AbstractHttpConfigurer::disable
                    )
                    .authorizeHttpRequests(authorize ->
                            authorize
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
                get("/api/organizations")
        ).andExpect(
                status().is4xxClientError()
        );
    }

    @Test
    void findAllWithUserRoleReturns403()
            throws Exception {
        mockMvc.perform(
                get("/api/organizations")
                        .with(authentication(
                                authToken(
                                        userPrincipal()
                                )
                        ))
        ).andExpect(
                status().isForbidden()
        );

        verify(
                organizationService,
                never()
        ).findAll(
                any(),
                any()
        );
    }

    @Test
    void findAllReturnsStablePageContract()
            throws Exception {
        when(organizationService.findAll(
                any(SafeAiUserPrincipal.class),
                any()
        )).thenReturn(
                new PageImpl<>(
                        List.of(
                                tenantResponse()
                        ),
                        PageRequest.of(
                                0,
                                20
                        ),
                        1L
                )
        );

        mockMvc.perform(
                get("/api/organizations")
                        .with(authentication(
                                authToken(
                                        adminPrincipal()
                                )
                        ))
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.page")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.size")
                                .value(20)
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.totalPages")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.content[0].id")
                                .value(
                                        ORGANIZATION_ID
                                                .toString()
                                )
                )
                .andExpect(
                        jsonPath("$.content[0].type")
                                .value("TENANT")
                )
                .andExpect(
                        jsonPath("$.content[0].protected")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$.content[0].version")
                                .value(VERSION)
                );
    }

    @Test
    void directoryWithAdminRoleReturns403()
            throws Exception {
        mockMvc.perform(
                get(
                        "/api/organizations/directory"
                )
                        .with(authentication(
                                authToken(
                                        adminPrincipal()
                                )
                        ))
        ).andExpect(
                status().isForbidden()
        );

        verify(
                organizationService,
                never()
        ).findDirectory(
                any(),
                anyInt(),
                any()
        );
    }

    @Test
    void directoryWithSuperAdminReturnsVersionedItems()
            throws Exception {
        when(organizationService.findDirectory(
                eq("Demo"),
                eq(20),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(
                List.of(
                        new OrganizationDirectoryResponse(
                                ORGANIZATION_ID,
                                "Demo Company",
                                true,
                                OrganizationType.TENANT,
                                false,
                                VERSION
                        )
                )
        );

        mockMvc.perform(
                get(
                        "/api/organizations/directory"
                )
                        .param(
                                "query",
                                "Demo"
                        )
                        .param(
                                "limit",
                                "20"
                        )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[0].id")
                                .value(
                                        ORGANIZATION_ID
                                                .toString()
                                )
                )
                .andExpect(
                        jsonPath("$[0].type")
                                .value("TENANT")
                )
                .andExpect(
                        jsonPath("$[0].protected")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$[0].version")
                                .value(VERSION)
                );
    }

    @Test
    void directoryRejectsLimitAboveMaximum()
            throws Exception {
        mockMvc.perform(
                get(
                        "/api/organizations/directory"
                )
                        .param(
                                "limit",
                                "51"
                        )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
        ).andExpect(
                status().isBadRequest()
        );

        verify(
                organizationService,
                never()
        ).findDirectory(
                any(),
                anyInt(),
                any()
        );
    }

    @Test
    void createWithAdminRoleReturns403()
            throws Exception {
        mockMvc.perform(
                post("/api/organizations")
                        .with(authentication(
                                authToken(
                                        adminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "name": "Tenant"
                                }
                                """)
        ).andExpect(
                status().isForbidden()
        );

        verify(
                organizationService,
                never()
        ).create(
                any(),
                any()
        );
    }

    @Test
    void createWithSuperAdminReturns201()
            throws Exception {
        when(organizationService.create(
                any(CreateOrganizationRequest.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(
                tenantResponse()
        );

        mockMvc.perform(
                post("/api/organizations")
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "name": "SafeAI"
                                }
                                """)
        )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(
                                        ORGANIZATION_ID
                                                .toString()
                                )
                )
                .andExpect(
                        jsonPath("$.version")
                                .value(VERSION)
                );
    }

    @Test
    void updateNameWithoutExpectedVersionReturns400()
            throws Exception {
        mockMvc.perform(
                patch(
                        "/api/organizations/{id}",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "name": "New Name"
                                }
                                """)
        )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath(
                                "$.fieldErrors.expectedVersion[0]"
                        ).exists()
                );

        verify(
                organizationService,
                never()
        ).updateName(
                any(),
                any(),
                any()
        );
    }

    @Test
    void updateNameReturnsSpecificVersionConflictCode()
            throws Exception {
        when(organizationService.updateName(
                eq(ORGANIZATION_ID),
                any(UpdateOrganizationRequest.class),
                any(SafeAiUserPrincipal.class)
        )).thenThrow(
                new OrganizationVersionConflictException(
                        ORGANIZATION_ID,
                        VERSION,
                        VERSION + 1L
                )
        );

        mockMvc.perform(
                patch(
                        "/api/organizations/{id}",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "name": "New Name",
                                  "expectedVersion": 7
                                }
                                """)
        )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "ORGANIZATION_VERSION_CONFLICT"
                                )
                );
    }

    @Test
    void updateNameWithAdminRoleAndValidBodyReturns403()
            throws Exception {
        mockMvc.perform(
                patch(
                        "/api/organizations/{id}",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        adminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "name": "New Name",
                                  "expectedVersion": 7
                                }
                                """)
        ).andExpect(
                status().isForbidden()
        );

        verify(
                organizationService,
                never()
        ).updateName(
                any(),
                any(),
                any()
        );
    }

    @Test
    @SuppressWarnings("deprecation")
    void compatibilityEnabledEndpointRequiresExpectedVersion()
            throws Exception {
        mockMvc.perform(
                patch(
                        "/api/organizations/{id}/enabled",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "enabled": false
                                }
                                """)
        ).andExpect(
                status().isBadRequest()
        );

        verify(
                organizationService,
                never()
        ).updateEnabled(
                any(),
                any(),
                any()
        );
    }

    @Test
    void disableImpactWithAdminRoleReturns403()
            throws Exception {
        mockMvc.perform(
                get(
                        "/api/organizations/{id}/disable-impact",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        adminPrincipal()
                                )
                        ))
        ).andExpect(
                status().isForbidden()
        );
    }

    @Test
    void disableImpactWithSuperAdminReturnsCounts()
            throws Exception {
        when(organizationService.getDisableImpact(
                eq(ORGANIZATION_ID),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(
                new OrganizationDisableImpactResponse(
                        ORGANIZATION_ID,
                        VERSION,
                        12L,
                        2L,
                        5L,
                        1L
                )
        );

        mockMvc.perform(
                get(
                        "/api/organizations/{id}/disable-impact",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.organizationVersion")
                                .value(VERSION)
                )
                .andExpect(
                        jsonPath("$.enabledUsers")
                                .value(12)
                )
                .andExpect(
                        jsonPath("$.activeChatOperations")
                                .value(1)
                );
    }

    @Test
    void disableRequiresConfirmationNameAndVersion()
            throws Exception {
        mockMvc.perform(
                post(
                        "/api/organizations/{id}/disable",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "expectedVersion": 7
                                }
                                """)
        ).andExpect(
                status().isBadRequest()
        );

        verify(
                organizationService,
                never()
        ).disable(
                any(),
                any(),
                any()
        );
    }

    @Test
    void disableWithValidContractReturnsUpdatedVersion()
            throws Exception {
        OrganizationResponse disabled =
                new OrganizationResponse(
                        ORGANIZATION_ID,
                        "Demo Company",
                        false,
                        OrganizationType.TENANT,
                        false,
                        VERSION + 1L,
                        NOW,
                        NOW
                );

        when(organizationService.disable(
                eq(ORGANIZATION_ID),
                any(DisableOrganizationRequest.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(disabled);

        mockMvc.perform(
                post(
                        "/api/organizations/{id}/disable",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "expectedVersion": 7,
                                  "confirmationName": "Demo Company"
                                }
                                """)
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.enabled")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$.version")
                                .value(VERSION + 1L)
                );
    }

    @Test
    void enableWithValidContractReturnsUpdatedVersion()
            throws Exception {
        OrganizationResponse enabled =
                new OrganizationResponse(
                        ORGANIZATION_ID,
                        "Demo Company",
                        true,
                        OrganizationType.TENANT,
                        false,
                        VERSION + 1L,
                        NOW,
                        NOW
                );

        when(organizationService.enable(
                eq(ORGANIZATION_ID),
                any(EnableOrganizationRequest.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(enabled);

        mockMvc.perform(
                post(
                        "/api/organizations/{id}/enable",
                        ORGANIZATION_ID
                )
                        .with(authentication(
                                authToken(
                                        superAdminPrincipal()
                                )
                        ))
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "expectedVersion": 7
                                }
                                """)
        )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.enabled")
                                .value(true)
                )
                .andExpect(
                        jsonPath("$.version")
                                .value(VERSION + 1L)
                );
    }

    private OrganizationResponse tenantResponse() {
        return new OrganizationResponse(
                ORGANIZATION_ID,
                "Demo Company",
                true,
                OrganizationType.TENANT,
                false,
                VERSION,
                NOW,
                NOW
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
                        "user@test.com",
                        0L,
                        0L,
                        List.of(
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
                        "admin@test.com",
                        0L,
                        0L,
                        List.of(
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
                        "superadmin@test.com",
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }
}