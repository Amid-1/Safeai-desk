package ru.safeai.gateway.organization.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.service.OrganizationService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrganizationController.class)
@Import({
        OrganizationControllerSecurityTest.TestMethodSecurityConfig.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class OrganizationControllerSecurityTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
    @EnableMethodSecurity
    static class TestMethodSecurityConfig {
    }

    @Test
    void findAllWithoutAuthenticationReturns4xx() throws Exception {
        mockMvc.perform(get("/api/organizations"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "user@test.com", roles = "USER")
    void findAllWithUserRoleReturns403() throws Exception {
        mockMvc.perform(get("/api/organizations"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void findAllWithAdminRoleReturns200() throws Exception {
        when(organizationService.findAll()).thenReturn(List.of(
                new OrganizationResponse(
                        ORGANIZATION_ID,
                        "SafeAI",
                        Instant.parse("2026-06-12T12:00:00Z")
                )
        ));

        mockMvc.perform(get("/api/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("SafeAI"));
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void createWithBlankNameReturns400() throws Exception {
        mockMvc.perform(post("/api/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": ""
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(organizationService, never()).create(any());
    }
}