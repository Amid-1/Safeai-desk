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
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.service.OrganizationService;

import java.time.Instant;
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
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID ADMIN_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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
        mockMvc.perform(get("/api/organizations"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void findAllWithUserRoleReturns403() throws Exception {
        mockMvc.perform(get("/api/organizations")
                        .with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void findAllWithAdminRoleReturns200() throws Exception {
        when(organizationService.findAll(
                any(SafeAiUserPrincipal.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(
                new OrganizationResponse(
                        ORGANIZATION_ID,
                        "SafeAI",
                        true,
                        Instant.parse("2026-06-12T12:00:00Z")
                )
        )));

        mockMvc.perform(get("/api/organizations")
                        .with(authentication(authToken(adminPrincipal()))))
                .andExpect(status().isOk());

        verify(organizationService).findAll(
                any(SafeAiUserPrincipal.class),
                any(Pageable.class)
        );
    }

    @Test
    void createWithBlankNameReturns400() throws Exception {
        mockMvc.perform(post("/api/organizations")
                        .with(authentication(authToken(superAdminPrincipal())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": ""
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(organizationService, never()).create(any(), any());
    }

    private Authentication authToken(SafeAiUserPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
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

    private SafeAiUserPrincipal superAdminPrincipal() {
        return new SafeAiUserPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "superadmin@test.com",
                "encoded-password",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
    }
}