package ru.safeai.gateway.chat.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import org.springframework.context.annotation.ComponentScan;

import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import org.springframework.context.annotation.FilterType;
import ru.safeai.gateway.common.security.UserStatusFilter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ChatController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        ChatControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class ChatControllerSecurityTest {

    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ORGANIZATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CHAT_ID = UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

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
        mockMvc.perform(get("/api/chats"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void findAllWithAuthenticatedUserReturns200() throws Exception {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatService.findAll(any(SafeAiUserPrincipal.class))).thenReturn(List.of(
                new ChatResponse(
                        CHAT_ID,
                        "Первый тестовый чат",
                        Instant.parse("2026-06-12T12:00:00Z")
                )
        ));

        mockMvc.perform(get("/api/chats")
                        .with(authentication(authToken(currentUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CHAT_ID.toString()))
                .andExpect(jsonPath("$[0].title").value("Первый тестовый чат"));
    }

    @Test
    void sendMessageWithEmptyContentReturns400() throws Exception {
        SafeAiUserPrincipal currentUser = currentUser();

        mockMvc.perform(post("/api/chats/{id}/messages", CHAT_ID)
                        .with(authentication(authToken(currentUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": ""
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(
                eq(CHAT_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        );
    }

    private Authentication authToken(SafeAiUserPrincipal currentUser) {
        return new UsernamePasswordAuthenticationToken(
                currentUser,
                null,
                currentUser.getAuthorities()
        );
    }

    private SafeAiUserPrincipal currentUser() {
        return new SafeAiUserPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "password-hash",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}