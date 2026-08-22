package ru.safeai.gateway.chat.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
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
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.dto.SendMessageResponse;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.service.ChatMapper;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Clock;
import java.time.Duration;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
            ChatTestFixtures.USER_ID;

    private static final UUID ORGANIZATION_ID =
            ChatTestFixtures.ORGANIZATION_ID;

    private static final UUID CHAT_ID =
            ChatTestFixtures.CHAT_ID;

    private static final UUID CLIENT_REQUEST_ID =
            ChatTestFixtures.CLIENT_REQUEST_ID;

    private static final Clock CLOCK =
            ChatTestFixtures.CLOCK;

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
            return CLOCK;
        }

        @Bean
        ChatProperties chatProperties() {
            return new ChatProperties(
                    50,
                    50,
                    100,
                    100,
                    16_000,
                    Duration.ofMinutes(3),
                    4,
                    1_000
            );
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(
                HttpSecurity http
        ) {
            return http
                    .csrf(
                            AbstractHttpConfigurer::disable
                    )
                    .authorizeHttpRequests(authorize ->
                            authorize
                                    .anyRequest()
                                    .authenticated()
                    )
                    .build();
        }
    }

    @Test
    void findAllWithoutAuthenticationReturns4xx()
            throws Exception {
        mockMvc.perform(
                        get("/api/chats")
                )
                .andExpect(
                        status().is4xxClientError()
                );

        verify(
                chatService,
                never()
        ).findAll(
                any(SafeAiUserPrincipal.class),
                any(Pageable.class)
        );
    }

    @Test
    void findAllWithAuthenticatedUserReturns200()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                currentUser();

        when(chatService.findAll(
                eq(currentUser),
                any(Pageable.class)
        )).thenReturn(
                new SliceImpl<>(
                        List.of(
                                new ChatResponse(
                                        CHAT_ID,
                                        "Первый тестовый чат",
                                        ChatTestFixtures.NOW,
                                        ChatTestFixtures.NOW.plusSeconds(60)
                                )
                        ),
                        PageRequest.of(
                                0,
                                20
                        ),
                        false
                )
        );

        mockMvc.perform(
                        get("/api/chats")
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.content[0].id")
                                .value(CHAT_ID.toString())
                )
                .andExpect(
                        jsonPath("$.content[0].title")
                                .value("Первый тестовый чат")
                )
                .andExpect(
                        jsonPath("$.content[0].updatedAt")
                                .exists()
                );

        verify(chatService).findAll(
                eq(currentUser),
                argThat(pageable ->
                        pageable.getPageNumber() == 0
                                && pageable.getPageSize() == 20
                )
        );
    }

    @Test
    void archiveWithAuthenticatedUserReturns204() throws Exception {
        SafeAiUserPrincipal currentUser = currentUser();

        mockMvc.perform(
                        delete("/api/chats/{chatId}", CHAT_ID)
                                .with(authentication(authToken(currentUser)))
                )
                .andExpect(status().isNoContent());

        verify(chatService).archive(CHAT_ID, currentUser);
    }

    @Test
    void createWithAuthenticatedUserReturns201()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                currentUser();

        when(chatService.create(
                any(),
                eq(currentUser)
        )).thenReturn(
                new ChatResponse(
                        CHAT_ID,
                        "Новый чат",
                        ChatTestFixtures.NOW,
                        ChatTestFixtures.NOW
                )
        );

        mockMvc.perform(
                        post("/api/chats")
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "title": "Новый чат"
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isCreated()
                )
                .andExpect(
                        jsonPath("$.id")
                                .value(CHAT_ID.toString())
                )
                .andExpect(
                        jsonPath("$.title")
                                .value("Новый чат")
                );

        verify(chatService).create(
                argThat(request ->
                        "Новый чат".equals(
                                request.title()
                        )
                ),
                eq(currentUser)
        );
    }

    @Test
    void sendMessageWithEmptyContentReturns400()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                currentUser();

        mockMvc.perform(
                        post(
                                "/api/chats/{chatId}/messages",
                                CHAT_ID
                        )
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "content": "",
                                          "clientRequestId": "%s"
                                        }
                                        """.formatted(
                                                CLIENT_REQUEST_ID
                                        )
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );

        verify(
                chatService,
                never()
        ).sendMessage(
                eq(CHAT_ID),
                any(SendMessageRequest.class),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void sendMessageAcceptsClientRequestId()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                currentUser();

        SendMessageResponse response =
                succeededResponse();

        when(chatService.sendMessage(
                eq(CHAT_ID),
                any(SendMessageRequest.class),
                eq(currentUser)
        )).thenReturn(response);

        mockMvc.perform(
                        post(
                                "/api/chats/{chatId}/messages",
                                CHAT_ID
                        )
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "content": "Привет",
                                          "clientRequestId": "%s"
                                        }
                                        """.formatted(
                                                CLIENT_REQUEST_ID
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.chatId")
                                .value(CHAT_ID.toString())
                )
                .andExpect(
                        jsonPath("$.turnId")
                                .value(
                                        ChatTestFixtures.TURN_ID
                                                .toString()
                                )
                )
                .andExpect(
                        jsonPath("$.clientRequestId")
                                .value(
                                        CLIENT_REQUEST_ID.toString()
                                )
                )
                .andExpect(
                        jsonPath("$.providerOperationId")
                                .value(
                                        ChatTestFixtures
                                                .PROVIDER_OPERATION_ID
                                                .toString()
                                )
                )
                .andExpect(
                        jsonPath("$.state")
                                .value("SUCCEEDED")
                )
                .andExpect(
                        jsonPath("$.replay")
                                .value(false)
                )
                .andExpect(
                        jsonPath(
                                "$.userMessage.clientRequestId"
                        ).value(
                                CLIENT_REQUEST_ID.toString()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.assistantMessage.replyToMessageId"
                        ).value(
                                ChatTestFixtures
                                        .USER_MESSAGE_ID
                                        .toString()
                        )
                );

        verify(chatService).sendMessage(
                eq(CHAT_ID),
                argThat(request ->
                        "Привет".equals(request.content())
                                && CLIENT_REQUEST_ID.equals(
                                request.clientRequestId()
                        )
                ),
                eq(currentUser)
        );
    }

    private static SendMessageResponse succeededResponse() {
        ChatMessageEntity user =
                ChatMessageEntity.user(
                        ChatTestFixtures.session(),
                        "Привет",
                        CLIENT_REQUEST_ID,
                        ChatTestFixtures.NOW.minusSeconds(10)
                );

        user.setId(
                ChatTestFixtures.USER_MESSAGE_ID
        );

        ChatMessageEntity assistant =
                ChatMessageEntity.completedAssistant(
                        ChatTestFixtures.ASSISTANT_MESSAGE_ID,
                        ChatTestFixtures.session(),
                        ChatTestFixtures.USER_MESSAGE_ID,
                        ChatTestFixtures.freeResponse(),
                        ChatTestFixtures.NOW
                );

        ChatMapper mapper =
                new ChatMapper();

        return new SendMessageResponse(
                CHAT_ID,
                ChatTestFixtures.TURN_ID,
                CLIENT_REQUEST_ID,
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                "SUCCEEDED",
                false,
                mapper.toMessageResponse(user),
                mapper.toMessageResponse(assistant),
                ChatTestFixtures.NOW,
                ChatTestFixtures.NOW.minusSeconds(10),
                ChatTestFixtures.NOW
        );
    }

    private static Authentication authToken(
            SafeAiUserPrincipal currentUser
    ) {
        return new UsernamePasswordAuthenticationToken(
                currentUser,
                null,
                currentUser.getAuthorities()
        );
    }

    private static SafeAiUserPrincipal currentUser() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }
}
