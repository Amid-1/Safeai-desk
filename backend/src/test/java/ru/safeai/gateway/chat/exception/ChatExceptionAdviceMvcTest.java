package ru.safeai.gateway.chat.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.controller.ChatController;
import ru.safeai.gateway.chat.service.ChatService;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
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
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies actual Spring MVC @ControllerAdvice resolution for ChatController,
 * not merely direct calls to exception-handler methods.
 */
@WebMvcTest(useDefaultFilters = false)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        ChatController.class,
        ChatApiExceptionHandler.class,
        ChatInfrastructureExceptionHandler.class,
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class,
        ChatExceptionAdviceMvcTest.TestBeans.class
})
class ChatExceptionAdviceMvcTest {

    private static final UUID CHAT_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        reset(chatService);
    }

    @Test
    void busyUsesChatInfrastructureAdviceAndStableApiErrorResponse()
            throws Exception {
        when(chatService.findById(
                eq(CHAT_ID),
                nullable(SafeAiUserPrincipal.class)
        )).thenThrow(new ChatBusyException(
                "В этот чат уже отправляется сообщение"
        ));

        mockMvc.perform(get("/api/chats/{chatId}", CHAT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CHAT_BUSY"))
                .andExpect(jsonPath("$.message")
                        .value("В этот чат уже отправляется сообщение"))
                .andExpect(jsonPath("$.path")
                        .value("/api/chats/" + CHAT_ID))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void unavailableUses503AndDoesNotExposeInternalCause()
            throws Exception {
        when(chatService.findById(
                eq(CHAT_ID),
                nullable(SafeAiUserPrincipal.class)
        )).thenThrow(new ChatLockUnavailableException(
                "Internal Redis lock error",
                new IllegalStateException("redis-password=secret")
        ));

        mockMvc.perform(get("/api/chats/{chatId}", CHAT_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error")
                        .value("CHAT_LOCK_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("Сервис блокировки чата временно недоступен"))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(not(containsString("redis-password"))))
                .andExpect(content().string(not(containsString("Internal Redis"))));
    }

    @Test
    void chatApiExceptionsKeepTheirSeparateChatErrorResponse()
            throws Exception {
        when(chatService.findById(
                eq(CHAT_ID),
                nullable(SafeAiUserPrincipal.class)
        )).thenThrow(new ProbeChatApiException(CHAT_ID));

        mockMvc.perform(get("/api/chats/{chatId}", CHAT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CHAT_PROTOCOL_TEST"))
                .andExpect(jsonPath("$.message").value("Chat protocol error"))
                .andExpect(jsonPath("$.chatId").value(CHAT_ID.toString()))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    private static final class ProbeChatApiException extends ChatApiException {

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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    Instant.parse("2026-08-12T12:00:00Z"),
                    ZoneOffset.UTC
            );
        }

        @Bean
        ChatService chatService() {
            return mock(ChatService.class);
        }

        @Bean
        ChatProperties chatProperties() {
            return new ChatProperties(
                    null, null, null, null,
                    null, null, null, null
            );
        }
    }
}
