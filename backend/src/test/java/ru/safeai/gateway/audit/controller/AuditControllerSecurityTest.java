package ru.safeai.gateway.audit.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
import ru.safeai.gateway.audit.service.AuditEventQueryService;
import ru.safeai.gateway.auth.security.UserStatusFilter;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuditController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        AuditControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
class AuditControllerSecurityTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ADMIN_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditEventQueryService auditEventQueryService;

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
                            .anyRequest().permitAll()
                    )
                    .build();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/audit-events",
            "/api/admin/audit-events/users/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    })
    void shouldReturn4xxWhenAnonymous(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().is4xxClientError());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/audit-events",
            "/api/admin/audit-events/users/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    })
    void shouldReturnForbiddenWhenUserRole(String url) throws Exception {
        mockMvc.perform(get(url)
                        .with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/audit-events",
            "/api/admin/audit-events/users/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    })
    void shouldReturnOkWhenAdminRole(String url) throws Exception {
        mockServices();

        mockMvc.perform(get(url)
                        .with(authentication(authToken(adminPrincipal()))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/audit-events",
            "/api/admin/audit-events/users/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    })
    void shouldReturnOkWhenSuperAdminRole(String url) throws Exception {
        mockServices();

        mockMvc.perform(get(url)
                        .with(authentication(authToken(superAdminPrincipal()))))
                .andExpect(status().isOk());
    }

    private void mockServices() {
        when(auditEventQueryService.findAll(
                any(SafeAiUserPrincipal.class),
                any(ru.safeai.gateway.audit.dto.AuditEventFilter.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        when(auditEventQueryService.findByUserId(
                eq(USER_ID),
                any(SafeAiUserPrincipal.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));
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
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return new SafeAiUserPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
    }
}