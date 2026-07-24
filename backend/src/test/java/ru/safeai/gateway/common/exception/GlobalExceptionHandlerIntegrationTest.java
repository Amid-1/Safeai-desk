package ru.safeai.gateway.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        useDefaultFilters = false
)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        GlobalExceptionHandlerIntegrationTest.ExceptionProbeController.class,
        GlobalExceptionHandler.class,
        GlobalExceptionHandlerIntegrationTest.FixedClockConfiguration.class
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GlobalExceptionHandlerIntegrationTest {

    private static final String REQUEST_ID = "integration-request-id";

    private final MockMvc mockMvc;

    GlobalExceptionHandlerIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void chatBusyExceptionIsHandledBySpecificHandler() throws Exception {
        mockMvc.perform(get("/test/errors/chat-busy")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.timestamp")
                        .value("2026-06-12T12:00:00Z"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CHAT_BUSY"))
                .andExpect(jsonPath("$.message").value(
                        "В этот чат уже отправляется сообщение"
                ))
                .andExpect(jsonPath("$.path")
                        .value("/test/errors/chat-busy"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void chatLockUnavailableExceptionIsHandledBySpecificHandler()
            throws Exception {
        mockMvc.perform(get("/test/errors/chat-lock-unavailable")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error")
                        .value("CHAT_LOCK_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "Сервис блокировки чата временно недоступен"
                ))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(content().string(not(containsString(
                        "redis.internal"
                ))));
    }

    @Test
    void optimisticLockingFailureExceptionUsesOptimisticLockHandler()
            throws Exception {
        mockMvc.perform(get("/test/errors/optimistic-lock")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "Данные были изменены другим пользователем. "
                                + "Обновите данные и повторите операцию"
                ))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unexpectedExceptionUsesSafeFallbackHandler() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("Внутренняя ошибка сервера"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(content().string(not(containsString(
                        "database password leaked"
                ))));
    }

    @Test
    void genericConflictStillUsesGenericConflictCode() throws Exception {
        mockMvc.perform(get("/test/errors/conflict")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("Email уже используется"));
    }

    @Test
    void requestBodyValidationReturnsStructuredFieldErrors()
            throws Exception {
        mockMvc.perform(post("/test/errors/validation")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name[0]")
                        .value("Имя обязательно"));
    }

    @Test
    void malformedJsonReturnsSafeBadRequest() throws Exception {
        mockMvc.perform(post("/test/errors/validation")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Некорректное тело запроса. "
                                + "Проверьте формат JSON и типы полей"
                ));
    }

    @Test
    void invalidControllerReturnValueIsTreatedAsServerError()
            throws Exception {
        mockMvc.perform(get("/test/errors/invalid-return")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("Внутренняя ошибка сервера"));
    }

    @Test
    void unsupportedHttpMethodReturns405AndAllowHeader() throws Exception {
        mockMvc.perform(post("/test/errors/chat-busy")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                .andExpect(jsonPath("$.error")
                        .value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void rateLimitExceptionReturnsRetryAfterHeader() throws Exception {
        mockMvc.perform(get("/test/errors/rate-limit")
                        .requestAttr(
                                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                                REQUEST_ID
                        ))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"))
                .andExpect(jsonPath("$.error")
                        .value("RATE_LIMIT_EXCEEDED"));
    }

    @RestController
    @RequestMapping("/test/errors")
    static class ExceptionProbeController {

        @GetMapping("/chat-busy")
        void chatBusy() {
            throw new ChatBusyException(
                    "В этот чат уже отправляется сообщение"
            );
        }

        @GetMapping("/chat-lock-unavailable")
        void chatLockUnavailable() {
            throw new ChatLockUnavailableException(
                    "Redis lock failed",
                    new RuntimeException("redis.internal:6379")
            );
        }

        @GetMapping("/optimistic-lock")
        void optimisticLock() {
            throw new OptimisticLockingFailureException(
                    "row was updated concurrently"
            );
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new RuntimeException("database password leaked");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("Email уже используется");
        }

        @GetMapping("/invalid-return")
        @NotBlank(message = "Возвращаемое значение не должно быть пустым")
        String invalidReturn() {
            return "";
        }

        @GetMapping("/rate-limit")
        void rateLimit() {
            throw new RateLimitExceededException(
                    "Превышен лимит запросов",
                    Duration.ofSeconds(45)
            );
        }

        @PostMapping("/validation")
        String validation(
                @Valid @RequestBody ValidationRequest request
        ) {
            return request.name();
        }
    }

    record ValidationRequest(
            @NotBlank(message = "Имя обязательно") String name
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        Clock testClock() {
            return Clock.fixed(
                    Instant.parse("2026-06-12T12:00:00Z"),
                    ZoneOffset.UTC
            );
        }

        @Bean
        ApiErrorResponseFactory apiErrorResponseFactory(Clock testClock) {
            return new ApiErrorResponseFactory(testClock);
        }
    }
}