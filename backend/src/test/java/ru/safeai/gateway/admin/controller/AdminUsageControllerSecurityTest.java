package ru.safeai.gateway.admin.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import ru.safeai.gateway.admin.service.AdminUsageService;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.JsonSecurityErrorWriter;

import ru.safeai.gateway.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUsageController.class)
@Import({
        AdminUsageControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class AdminUsageControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUsageService adminUsageService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JsonSecurityErrorWriter jsonSecurityErrorWriter;

    private static final UUID ADMIN_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");


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

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/usage-summary",
            "/api/admin/usage/summary",
            "/api/admin/usage/users",
            "/api/admin/usage/models",
            "/api/admin/usage/daily",
            "/api/admin/usage/by-user/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "/api/admin/usage/by-organization/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    })
    void shouldReturn4xxWhenAnonymous(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().is4xxClientError());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/usage-summary",
            "/api/admin/usage/summary",
            "/api/admin/usage/users",
            "/api/admin/usage/models",
            "/api/admin/usage/daily",
            "/api/admin/usage/by-user/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "/api/admin/usage/by-organization/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    })
    void shouldReturnForbiddenWhenUserRole(String url) throws Exception {
        mockMvc.perform(get(url)
                        .with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/usage-summary",
            "/api/admin/usage/summary",
            "/api/admin/usage/users",
            "/api/admin/usage/models",
            "/api/admin/usage/daily",
            "/api/admin/usage/by-user/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "/api/admin/usage/by-organization/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    })
    void shouldReturnOkWhenAdminRole(String url) throws Exception {
        mockServices();

        mockMvc.perform(get(url)
                        .with(authentication(authToken(adminPrincipal()))))
                .andExpect(status().isOk());
    }

    private void mockServices() {
        when(adminUsageService.getUsageSummary(any())).thenReturn(List.of());
        when(adminUsageService.getUsageByUsers()).thenReturn(List.of());
        when(adminUsageService.getUsageByModels()).thenReturn(List.of());
        when(adminUsageService.getUsageDaily()).thenReturn(List.of());
        when(adminUsageService.getUsageByUserId(any())).thenReturn(List.of());
        when(adminUsageService.getUsageByOrganizationId(any(), any())).thenReturn(List.of());
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
}