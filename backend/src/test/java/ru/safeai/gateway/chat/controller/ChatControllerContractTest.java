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
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.auth.security.UserStatusFilter;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.ChatResponse;
import ru.safeai.gateway.chat.dto.ChatTurnStatusResponse;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.dto.SendMessageResponse;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.chat.exception.ChatApiExceptionHandler;
import ru.safeai.gateway.chat.exception.ChatTurnInProgressException;
import ru.safeai.gateway.chat.service.ChatMapper;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        ChatControllerContractTest.TestSecurityConfig.class,
        ChatApiExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
class ChatControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {

        @Bean
        Clock clock() {
            return ChatTestFixtures.CLOCK;
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
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize ->
                            authorize
                                    .anyRequest()
                                    .authenticated()
                    )
                    .build();
        }
    }

    @Test
    void unauthenticatedAccessIsRejected()
            throws Exception {
        mockMvc.perform(
                        get("/api/chats")
                )
                .andExpect(
                        status().is4xxClientError()
                );
    }

    @Test
    void clientRequestIdIsMandatory()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/chats/{chatId}/messages",
                                ChatTestFixtures.CHAT_ID
                        )
                                .with(authentication(
                                        userAuthentication()
                                ))
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "content": "Question"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verify(
                chatService,
                never()
        ).sendMessage(
                any(),
                any(),
                any()
        );
    }

    @Test
    void emptyContentIsRejected()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/chats/{chatId}/messages",
                                ChatTestFixtures.CHAT_ID
                        )
                                .with(authentication(
                                        userAuthentication()
                                ))
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "content": "   ",
                                          "clientRequestId": "%s"
                                        }
                                        """.formatted(
                                        ChatTestFixtures.CLIENT_REQUEST_ID
                                ))
                )
                .andExpect(status().isBadRequest());

        verify(
                chatService,
                never()
        ).sendMessage(
                any(),
                any(),
                any()
        );
    }

    @Test
    void pageSizeAboveProductionMaximumIsRejected()
            throws Exception {
        mockMvc.perform(
                        get("/api/chats")
                                .param("size", "101")
                                .with(authentication(
                                        userAuthentication()
                                ))
                )
                .andExpect(status().isBadRequest());

        verify(
                chatService,
                never()
        ).findAll(
                any(),
                any()
        );
    }

    @Test
    void arbitraryNestedSortIsRejectedByBindingValidation()
            throws Exception {
        mockMvc.perform(
                        get("/api/chats")
                                .param(
                                        "sortBy",
                                        "user.passwordHash"
                                )
                                .with(authentication(
                                        userAuthentication()
                                ))
                )
                .andExpect(status().isBadRequest());

        verify(
                chatService,
                never()
        ).findAll(
                any(),
                any()
        );
    }

    @Test
    void findAllUsesBoundedDeterministicPageable()
            throws Exception {
        when(chatService.findAll(
                any(SafeAiUserPrincipal.class),
                any()
        )).thenReturn(
                new SliceImpl<>(
                        List.of(
                                new ChatResponse(
                                        ChatTestFixtures.CHAT_ID,
                                        "Chat",
                                        ChatTestFixtures.NOW,
                                        ChatTestFixtures.NOW
                                )
                        ),
                        PageRequest.of(0, 20),
                        false
                )
        );

        mockMvc.perform(
                        get("/api/chats")
                                .param(
                                        "sortBy",
                                        "updatedAt"
                                )
                                .param(
                                        "direction",
                                        "desc"
                                )
                                .with(authentication(
                                        userAuthentication()
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[0].id")
                                .value(
                                        ChatTestFixtures.CHAT_ID
                                                .toString()
                                )
                )
                .andExpect(
                        jsonPath("$.size")
                                .value(20)
                );

        verify(chatService).findAll(
                any(SafeAiUserPrincipal.class),
                argThat(pageable ->
                        pageable.getPageSize() == 20
                                && pageable
                                .getSort()
                                .getOrderFor("updatedAt") != null
                                && pageable
                                .getSort()
                                .getOrderFor("id") != null
                )
        );
    }

    @Test
    void sendReturnsOnlyCurrentTurnAndCorrelationMetadata()
            throws Exception {
        SendMessageResponse response =
                succeededResponse();

        when(chatService.sendMessage(
                eq(ChatTestFixtures.CHAT_ID),
                any(SendMessageRequest.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(response);

        mockMvc.perform(
                        post(
                                "/api/chats/{chatId}/messages",
                                ChatTestFixtures.CHAT_ID
                        )
                                .with(authentication(
                                        userAuthentication()
                                ))
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "content": "Question",
                                          "clientRequestId": "%s"
                                        }
                                        """.formatted(
                                        ChatTestFixtures.CLIENT_REQUEST_ID
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.turnId")
                                .value(
                                        ChatTestFixtures.TURN_ID
                                                .toString()
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
                                ChatTestFixtures
                                        .CLIENT_REQUEST_ID
                                        .toString()
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
                )
                .andExpect(
                        jsonPath(
                                "$.assistantMessage.aiResponseStatus"
                        ).value("COMPLETED")
                )
                .andExpect(
                        jsonPath(
                                "$.assistantMessage.providerRequestId"
                        ).value("provider-request-id")
                );
    }

    @Test
    void inProgressResponseHasStableCodeAndCeilingRetryAfterHeader()
            throws Exception {
        when(chatService.sendMessage(
                eq(ChatTestFixtures.CHAT_ID),
                any(SendMessageRequest.class),
                any(SafeAiUserPrincipal.class)
        )).thenThrow(
                new ChatTurnInProgressException(
                        ChatTestFixtures.CHAT_ID,
                        ChatTestFixtures.TURN_ID,
                        ChatTestFixtures.CLIENT_REQUEST_ID,
                        Duration.ofMillis(1_500)
                )
        );

        mockMvc.perform(
                        post(
                                "/api/chats/{chatId}/messages",
                                ChatTestFixtures.CHAT_ID
                        )
                                .with(authentication(
                                        userAuthentication()
                                ))
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "content": "Question",
                                          "clientRequestId": "%s"
                                        }
                                        """.formatted(
                                        ChatTestFixtures.CLIENT_REQUEST_ID
                                ))
                )
                .andExpect(status().isConflict())
                .andExpect(
                        header().string(
                                "Retry-After",
                                "2"
                        )
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "CHAT_TURN_IN_PROGRESS"
                                )
                )
                .andExpect(
                        jsonPath("$.turnId")
                                .value(
                                        ChatTestFixtures.TURN_ID
                                                .toString()
                                )
                )
                .andExpect(
                        jsonPath("$.retryAfterSeconds")
                                .value(2)
                );
    }

    @Test
    void turnStatusEndpointExposesTerminalStateWithoutProviderRetry()
            throws Exception {
        SendMessageResponse succeeded =
                succeededResponse();

        ChatTurnStatusResponse statusResponse =
                new ChatTurnStatusResponse(
                        succeeded.chatId(),
                        succeeded.turnId(),
                        succeeded.clientRequestId(),
                        succeeded.providerOperationId(),
                        "SUCCEEDED",
                        "mock",
                        "requested-model",
                        "resolved-model",
                        "provider-request-id",
                        null,
                        null,
                        false,
                        null,
                        ChatTestFixtures.NOW.minusSeconds(1),
                        ChatTestFixtures.NOW.minusSeconds(10),
                        ChatTestFixtures.NOW,
                        ChatTestFixtures.NOW,
                        succeeded.userMessage(),
                        succeeded.assistantMessage()
                );

        when(chatService.findTurnStatus(
                eq(ChatTestFixtures.CHAT_ID),
                eq(ChatTestFixtures.CLIENT_REQUEST_ID),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(statusResponse);

        mockMvc.perform(
                        get(
                                "/api/chats/{chatId}/turns/{clientRequestId}",
                                ChatTestFixtures.CHAT_ID,
                                ChatTestFixtures.CLIENT_REQUEST_ID
                        )
                                .with(authentication(
                                        userAuthentication()
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("SUCCEEDED")
                )
                .andExpect(
                        jsonPath("$.outcomeAmbiguous")
                                .value(false)
                );
    }

    private static UsernamePasswordAuthenticationToken userAuthentication() {
        SafeAiUserPrincipal principal =
                ChatTestFixtures.principal();

        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    private static SendMessageResponse succeededResponse() {
        ChatMessageEntity user =
                ChatMessageEntity.user(
                        ChatTestFixtures.session(),
                        "Question",
                        ChatTestFixtures.CLIENT_REQUEST_ID,
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

        ChatMapper mapper = new ChatMapper();

        return new SendMessageResponse(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
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
}