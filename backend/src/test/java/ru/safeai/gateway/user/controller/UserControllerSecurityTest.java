package ru.safeai.gateway.user.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.service.UserService;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(UserControllerSecurityTest.TestMethodSecurityConfig.class)
@ActiveProfiles("test")
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
    @EnableMethodSecurity
    static class TestMethodSecurityConfig {
    }

    @Test
    void findAllWithoutAuthenticationReturnsUnauthorizedOrForbidden() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "user@test.com", roles = "USER")
    void findAllWithUserRoleReturns403() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void findAllWithAdminRoleReturns200() throws Exception {
        UUID userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID organizationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        when(userService.findAll()).thenReturn(List.of(
                new UserResponse(
                        userId,
                        organizationId,
                        "admin@test.com",
                        "Demo Admin",
                        true,
                        Set.of("ADMIN"),
                        Instant.parse("2026-05-31T12:00:00Z")
                )
        ));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(userId.toString()))
                .andExpect(jsonPath("$[0].organizationId").value(organizationId.toString()))
                .andExpect(jsonPath("$[0].email").value("admin@test.com"))
                .andExpect(jsonPath("$[0].fullName").value("Demo Admin"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].roles[0]").value("ADMIN"));
    }
}