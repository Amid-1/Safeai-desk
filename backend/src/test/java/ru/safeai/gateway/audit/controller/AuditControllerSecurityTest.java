package ru.safeai.gateway.audit.controller;

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
import ru.safeai.gateway.audit.service.AuditEventQueryService;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditController.class)
@Import({
        AuditControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class AuditControllerSecurityTest {

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
        when(auditEventQueryService.findAll()).thenReturn(List.of());
        when(auditEventQueryService.findByUserId(any())).thenReturn(List.of());

        mockMvc.perform(get(url)
                        .with(user("admin@test.com").roles("ADMIN")))
                .andExpect(status().isOk());
    }
}