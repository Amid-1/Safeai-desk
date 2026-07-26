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
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.service.OrganizationService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
            Instant.parse("2026-06-12T12:00:00Z");

    private static final long TOKEN_VERSION = 0L;
    private static final long ORGANIZATION_AUTH_VERSION = 0L;

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
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(
                HttpSecurity http
        ) {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize ->
                            authorize.anyRequest().authenticated()
                    )
                    .build();
        }
    }

    @Test
    void findAllWithoutAuthenticationReturns4xx()
            throws Exception {
        mockMvc.perform(get("/api/organizations"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void findAllWithUserRoleReturns403()
            throws Exception {
        mockMvc.perform(
                        get("/api/organizations")
                                .with(
                                        user("user@test.com")
                                                .roles("USER")
                                )
                )
                .andExpect(status().isForbidden());

        verify(
                organizationService,
                never()
        ).findAll(any(), any());
    }

    @Test
    void findAllWithAdminRoleReturns200()
            throws Exception {
        when(organizationService.findAll(
                any(SafeAiUserPrincipal.class),
                any(Pageable.class)
        )).thenReturn(
                new PageImpl<>(
                        List.of(response())
                )
        );

        mockMvc.perform(
                        get("/api/organizations")
                                .with(authentication(
                                        authToken(adminPrincipal())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id")
                        .value(ORGANIZATION_ID.toString()));

        verify(organizationService).findAll(
                any(SafeAiUserPrincipal.class),
                any(Pageable.class)
        );
    }

    @Test
    void createWithAdminRoleReturns403()
            throws Exception {
        mockMvc.perform(
                        post("/api/organizations")
                                .with(authentication(
                                        authToken(adminPrincipal())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "Tenant"
                                        }
                                        """)
                )
                .andExpect(status().isForbidden());

        verify(
                organizationService,
                never()
        ).create(any(), any());
    }

    @Test
    void createWithSuperAdminRoleReturns201()
            throws Exception {
        when(organizationService.create(
                any(CreateOrganizationRequest.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(response());

        mockMvc.perform(
                        post("/api/organizations")
                                .with(authentication(
                                        authToken(superAdminPrincipal())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "SafeAI"
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id")
                        .value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.createdAt")
                        .value(NOW.toString()));

        verify(organizationService).create(
                any(CreateOrganizationRequest.class),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void createWithBlankNameReturns400()
            throws Exception {
        mockMvc.perform(
                        post("/api/organizations")
                                .with(authentication(
                                        authToken(superAdminPrincipal())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": ""
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verify(
                organizationService,
                never()
        ).create(any(), any());
    }

    private OrganizationResponse response() {
        return new OrganizationResponse(
                ORGANIZATION_ID,
                "SafeAI",
                true,
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

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                TOKEN_VERSION,
                ORGANIZATION_AUTH_VERSION,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                TOKEN_VERSION,
                ORGANIZATION_AUTH_VERSION,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_SUPER_ADMIN"
                        )
                )
        );
    }
}
