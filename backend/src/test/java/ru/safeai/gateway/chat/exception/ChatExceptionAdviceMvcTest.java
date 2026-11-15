package ru.safeai.gateway.chat.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.controller.ChatController;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Spring MVC dispatch and @ControllerAdvice resolution without bootstrapping
 * unrelated application infrastructure (DB, Redis, JWT, S3, scheduled workers).
 *
 * <p>Uses actual ChatController and all three production exception handlers,
 * not mocks of the advice or direct invocations of handler methods. Method
 * security and HTTP authentication are verified by separate security tests;
 * this test checks only MVC exception mapping and safe response bodies.</p>
 */
class ChatExceptionAdviceMvcTest {

    private static final UUID CHAT_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC
    );

    private ChatService chatService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        ApiErrorResponseFactory responses =
                new ApiErrorResponseFactory(FIXED_CLOCK);

        // The real Spring MVC DispatcherServlet selects the real controller
        // and resolves actual @ExceptionHandler methods by advice precedence.
        // Avoid @WebMvcTest's unrelated full application context initialization.
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ChatController(chatService, new ChatProperties(
                                null, null, null, null,
                                null, null, null, null
                        ))
                )
                .setControllerAdvice(
                        new ChatApiExceptionHandler(FIXED_CLOCK),
                        new ChatInfrastructureExceptionHandler(responses),
                        new GlobalExceptionHandler(responses)
                )
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .build();
    }

    @Test
    void busyUsesChatInfrastructureAdviceAndStableApiErrorResponse()
            throws Exception {
        when(chatService.findById(
                eq(CHAT_ID), nullable(SafeAiUserPrincipal.class)
        )).thenThrow(new ChatBusyException(
                "В этот чат уже отправляется сообщение"
        ));

        mockMvc.perform(get("/api/chats/{chatId}", CHAT_ID))
                .andExpect(handler().handlerType(ChatController.class))
                .andExpect(handler().methodName("findById"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CHAT_BUSY"))
                .andExpect(jsonPath("$.message")
                        .value("В этот чат уже отправляется сообщение"))
                .andExpect(jsonPath("$.path")
                        .value("/api/chats/" + CHAT_ID))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(chatService).findById(eq(CHAT_ID),
                nullable(SafeAiUserPrincipal.class));
        verifyNoMoreInteractions(chatService);
    }

    @Test
    void unavailableUses503AndDoesNotExposeInternalCause()
            throws Exception {
        when(chatService.findById(
                eq(CHAT_ID), nullable(SafeAiUserPrincipal.class)
        )).thenThrow(new ChatLockUnavailableException(
                "Internal Redis lock error",
                new IllegalStateException("redis-password=secret")
        ));

        mockMvc.perform(get("/api/chats/{chatId}", CHAT_ID))
                .andExpect(handler().handlerType(ChatController.class))
                .andExpect(handler().methodName("findById"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error")
                        .value("CHAT_LOCK_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("Сервис блокировки чата временно недоступен"))
                .andExpect(jsonPath("$.path")
                        .value("/api/chats/" + CHAT_ID))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(
                        not(containsString("redis-password"))))
                .andExpect(content().string(
                        not(containsString("Internal Redis"))));

        verify(chatService).findById(eq(CHAT_ID),
                nullable(SafeAiUserPrincipal.class));
        verifyNoMoreInteractions(chatService);
    }

    @Test
    void chatApiExceptionsKeepTheirSeparateChatErrorResponse()
            throws Exception {
        when(chatService.findById(
                eq(CHAT_ID), nullable(SafeAiUserPrincipal.class)
        )).thenThrow(new ProbeChatApiException(CHAT_ID));

        mockMvc.perform(get("/api/chats/{chatId}", CHAT_ID))
                .andExpect(handler().handlerType(ChatController.class))
                .andExpect(handler().methodName("findById"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code")
                        .value("CHAT_PROTOCOL_TEST"))
                .andExpect(jsonPath("$.message")
                        .value("Chat protocol error"))
                .andExpect(jsonPath("$.chatId")
                        .value(CHAT_ID.toString()))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());

        verify(chatService).findById(eq(CHAT_ID),
                nullable(SafeAiUserPrincipal.class));
        verifyNoMoreInteractions(chatService);
    }

    @Test
    void commonBadRequestUsesGlobalAdviceRatherThanChatSpecificHandlers()
            throws Exception {
        when(chatService.findById(
                eq(CHAT_ID), nullable(SafeAiUserPrincipal.class)
        )).thenThrow(new BadRequestException("Некорректный запрос чата"));

        mockMvc.perform(get("/api/chats/{chatId}", CHAT_ID))
                .andExpect(handler().handlerType(ChatController.class))
                .andExpect(handler().methodName("findById"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Некорректный запрос чата"))
                .andExpect(jsonPath("$.path")
                        .value("/api/chats/" + CHAT_ID))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(chatService).findById(eq(CHAT_ID),
                nullable(SafeAiUserPrincipal.class));
        verifyNoMoreInteractions(chatService);
    }

    private static final class ProbeChatApiException
            extends ChatApiException {
        private ProbeChatApiException(UUID chatId) {
            super(
                    HttpStatus.CONFLICT,
                    "CHAT_PROTOCOL_TEST",
                    "Chat protocol error",
                    chatId,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}
