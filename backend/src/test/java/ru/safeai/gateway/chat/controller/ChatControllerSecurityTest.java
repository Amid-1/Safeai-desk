package ru.safeai.gateway.chat.controller;

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
import ru.safeai.gateway.chat.dto.ChatDetailsResponse;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
class ChatControllerSecurityTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID CHAT_ID =
            UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

    private static final Instant NOW =
            Instant.parse("2026-06-12T12:00:00Z");

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
    void findAllWithoutAuthenticationReturns4xx() throws Exception {
        mockMvc.perform(get("/api/chats"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void findAllWithAuthenticatedUserReturns200() throws Exception {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatService.findAll(
                any(SafeAiUserPrincipal.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(
                        new ChatResponse(
                                CHAT_ID,
                                "Первый тестовый чат",
                                NOW,
                                NOW.plusSeconds(60)
                        )
                )
        ));

        mockMvc.perform(
                        get("/api/chats")
                                .with(authentication(
                                        authToken(currentUser)
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id")
                        .value(CHAT_ID.toString()))
                .andExpect(jsonPath("$.content[0].title")
                        .value("Первый тестовый чат"))
                .andExpect(jsonPath("$.content[0].updatedAt")
                        .exists());

        verify(chatService).findAll(
                any(SafeAiUserPrincipal.class),
                any(Pageable.class)
        );
    }

    @Test
    void createWithAuthenticatedUserReturns201() throws Exception {
        SafeAiUserPrincipal currentUser = currentUser();

        when(chatService.create(
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(
                new ChatResponse(
                        CHAT_ID,
                        "Новый чат",
                        NOW,
                        NOW
                )
        );

        mockMvc.perform(
                        post("/api/chats")
                                .with(authentication(
                                        authToken(currentUser)
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "title": "Новый чат"
                                        }
                                        """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id")
                        .value(CHAT_ID.toString()));
    }

    @Test
    void sendMessageWithEmptyContentReturns400() throws Exception {
        SafeAiUserPrincipal currentUser = currentUser();

        mockMvc.perform(
                        post(
                                "/api/chats/{id}/messages",
                                CHAT_ID
                        )
                                .with(authentication(
                                        authToken(currentUser)
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "content": ""
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(
                eq(CHAT_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void sendMessageAcceptsClientRequestId() throws Exception {
        SafeAiUserPrincipal currentUser = currentUser();
        UUID clientRequestId = UUID.randomUUID();

        when(chatService.sendMessage(
                eq(CHAT_ID),
                any(),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(
                new ChatDetailsResponse(
                        CHAT_ID,
                        "Чат",
                        NOW,
                        NOW,
                        List.of()
                )
        );

        mockMvc.perform(
                        post(
                                "/api/chats/{id}/messages",
                                CHAT_ID
                        )
                                .with(authentication(
                                        authToken(currentUser)
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "content": "Привет",
                                          "clientRequestId": "%s"
                                        }
                                        """.formatted(clientRequestId))
                )
                .andExpect(status().isOk());

        verify(chatService).sendMessage(
                eq(CHAT_ID),
                argThat(request ->
                        "Привет".equals(request.content())
                                && clientRequestId.equals(
                                request.clientRequestId()
                        )
                ),
                any(SafeAiUserPrincipal.class)
        );
    }

    private Authentication authToken(
            SafeAiUserPrincipal currentUser
    ) {
        return new UsernamePasswordAuthenticationToken(
                currentUser,
                null,
                currentUser.getAuthorities()
        );
    }

    private SafeAiUserPrincipal currentUser() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }
}
