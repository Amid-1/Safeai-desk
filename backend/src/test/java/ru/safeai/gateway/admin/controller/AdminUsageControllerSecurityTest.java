package ru.safeai.gateway.admin.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.admin.service.AdminUsageService;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.common.security.JsonAccessDeniedHandler;
import ru.safeai.gateway.common.security.JsonAuthenticationEntryPoint;
import ru.safeai.gateway.common.security.SafeAiJwtAuthenticationConverter;
import ru.safeai.gateway.common.security.SecurityConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUsageController.class)
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
    private UserDetailsService userDetailsService;

    @MockitoBean
    private SafeAiJwtAuthenticationConverter safeAiJwtAuthenticationConverter;

    @Test
    void usageSummaryWithoutAuthenticationReturns401() throws Exception {
        mockMvc.perform(get("/api/admin/usage-summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user@test.com", roles = "USER")
    void usageSummaryWithUserRoleReturns403() throws Exception {
        mockMvc.perform(get("/api/admin/usage-summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void usageSummaryWithAdminRoleReturns200() throws Exception {
        UUID userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        when(adminUsageService.getUsageSummary()).thenReturn(List.of(
                new UsageSummaryResponse(
                        userId,
                        "admin@test.com",
                        "mock-safeai",
                        25L,
                        40L,
                        65L,
                        BigDecimal.ZERO
                )
        ));

        mockMvc.perform(get("/api/admin/usage-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(userId.toString()))
                .andExpect(jsonPath("$[0].userEmail").value("admin@test.com"))
                .andExpect(jsonPath("$[0].model").value("mock-safeai"))
                .andExpect(jsonPath("$[0].inputTokens").value(25))
                .andExpect(jsonPath("$[0].outputTokens").value(40))
                .andExpect(jsonPath("$[0].totalTokens").value(65))
                .andExpect(jsonPath("$[0].costUsd").value(0));
    }
}