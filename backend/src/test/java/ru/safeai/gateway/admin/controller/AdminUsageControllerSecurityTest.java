package ru.safeai.gateway.admin.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.admin.service.AdminUsageService;
import ru.safeai.gateway.common.security.JsonAccessDeniedHandler;
import ru.safeai.gateway.common.security.JsonAuthenticationEntryPoint;
import ru.safeai.gateway.common.security.SafeAiJwtAuthenticationConverter;
import ru.safeai.gateway.common.security.SecurityConfig;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AdminUsageController.class,
        excludeAutoConfiguration = OAuth2ResourceServerAutoConfiguration.class
)
@Import({
        SecurityConfig.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class
})
@ActiveProfiles("test")
class AdminUsageControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUsageService adminUsageService;

    @MockitoBean
    private SafeAiJwtAuthenticationConverter safeAiJwtAuthenticationConverter;

    @MockitoBean
    private UserDetailsService userDetailsService;

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
    void shouldReturnUnauthorizedWhenAnonymous(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @WithMockUser(roles = "USER")
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
        mockMvc.perform(get(url))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @WithMockUser(roles = "ADMIN")
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

        mockMvc.perform(get(url))
                .andExpect(status().isOk());
    }

    private void mockServices() {
        when(adminUsageService.getUsageSummary()).thenReturn(List.of());
        when(adminUsageService.getUsageByUsers()).thenReturn(List.of());
        when(adminUsageService.getUsageByModels()).thenReturn(List.of());
        when(adminUsageService.getUsageDaily()).thenReturn(List.of());
        when(adminUsageService.getUsageByUserId(any())).thenReturn(List.of());
        when(adminUsageService.getUsageByOrganizationId(any())).thenReturn(List.of());
    }
}