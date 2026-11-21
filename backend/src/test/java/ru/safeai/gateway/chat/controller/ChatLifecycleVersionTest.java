package ru.safeai.gateway.chat.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import java.time.Clock;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.dto.ChatErasureRequest;
import ru.safeai.gateway.chat.service.ChatLifecycleService;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import org.springframework.http.HttpHeaders;

/**
 * Verifies the real MVC header-to-controller contract, not just a direct call
 * that IDEA correctly recognises as guaranteed to throw for literal null.
 * Standalone MockMvc does not enable @PreAuthorize; auth is tested separately.
 */
class ChatLifecycleVersionTest {
    private static final UUID CHAT_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String IDEMPOTENCY_KEY =
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    private ChatLifecycleService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ChatLifecycleService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new ChatLifecycleController(service, mock(ChatProperties.class))
        ).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(new ApiErrorResponseFactory(Clock.systemUTC())))
                .build();
    }

    @Test
    void missingIfMatchIsHttp428AndNeverAcceptsErasure() throws Exception {
        mvc.perform(erasureRequest())
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.status").value(428))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "Reject malformed strong ETag: {0}")
    @ValueSource(strings = {
            "", "1", "*", "W/\"1\"", "\"-1\"", "\"+1\"", "\"01\"",
            "\"x\"", "\"1\",\"2\"", " \"1\"", "\"1\" ",
            "\"9223372036854775808\"",
            "\"9999999999999999999999999999999999999999\""
    })
    void malformedIfMatchIsHttp400AndNeverAcceptsErasure(String invalid)
            throws Exception {
        mvc.perform(erasureRequest().header("If-Match", invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "Accept strong version tag {0}")
    @MethodSource("validVersions")
    void strongIfMatchIsForwardedAsExactVersion(String ifMatch, long expected)
            throws Exception {
        mvc.perform(erasureRequest().header("If-Match", ifMatch))
                .andExpect(status().isAccepted());

        verify(service).acceptErasure(
                eq(CHAT_ID),
                eq(expected),
                eq(IDEMPOTENCY_KEY),
                argThat((ChatErasureRequest request) ->
                        "Example chat".equals(request.confirmationTitle())
                                && "УДАЛИТЬ НАВСЕГДА".equals(request.confirmationPhrase())),
                isNull()
        );
    }

    @Test
    void missingHeaderDoesNotGetSilentlyConvertedToVersionZero()
            throws Exception {
        // A missing precondition must not be treated as a valid "0" ETag.
        assertThat(ChatLifecycleController.parseVersion("\"0\"")).isZero();
        mvc.perform(erasureRequest())
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.status").value(428))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"));
        verifyNoInteractions(service);
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> validVersions() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("\"0\"", 0L),
                org.junit.jupiter.params.provider.Arguments.of("\"42\"", 42L),
                org.junit.jupiter.params.provider.Arguments.of(
                        "\"9223372036854775807\"", Long.MAX_VALUE)
        );
    }

    private static MockHttpServletRequestBuilder erasureRequest() {
        return delete("/api/chats/{chatId}/erasure", CHAT_ID)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"confirmationTitle":"Example chat",
                         "confirmationPhrase":"УДАЛИТЬ НАВСЕГДА"}
                        """);
    }
}
