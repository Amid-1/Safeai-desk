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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers =
        GlobalExceptionHandlerIntegrationTest
                .ErrorProbeController.class
)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class,
        GlobalExceptionHandlerIntegrationTest
                .ClockConfiguration.class
})
class GlobalExceptionHandlerIntegrationTest {

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-12T12:00:00Z"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownApiUriReturns404NotFoundWithObjectFieldErrors()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/api/definitely-not-existing"
                        )
                )
                .andExpect(
                        status().isNotFound()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "NOT_FOUND"
                                )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(404)
                )
                .andExpect(
                        jsonPath("$.fieldErrors")
                                .isMap()
                );
    }

    @Test
    void wrongMethodReturns405AndPreservesAllow()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/test/errors/method-contract"
                        )
                )
                .andExpect(
                        status().isMethodNotAllowed()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "METHOD_NOT_ALLOWED"
                                )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.ALLOW,
                                containsString("GET")
                        )
                )
                .andExpect(
                        jsonPath("$.fieldErrors")
                                .isMap()
                );
    }

    @Test
    void rateLimitPreservesRetryAfter()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/test/errors/rate-limit"
                        )
                )
                .andExpect(
                        status().isTooManyRequests()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "RATE_LIMIT_EXCEEDED"
                                )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.RETRY_AFTER,
                                "2"
                        )
                )
                .andExpect(
                        jsonPath("$.fieldErrors")
                                .isMap()
                );
    }

    @Test
    void userVersionConflictUsesSpecificCode()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/test/errors/user-version-conflict"
                        )
                )
                .andExpect(
                        status().isConflict()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "USER_VERSION_CONFLICT"
                                )
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        not(
                                                containsString(
                                                        "expectedVersion"
                                                )
                                        )
                                )
                );
    }

    @Test
    void organizationVersionConflictUsesSpecificCode()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/test/errors/organization-version-conflict"
                        )
                )
                .andExpect(
                        status().isConflict()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "ORGANIZATION_VERSION_CONFLICT"
                                )
                );
    }

    @Test
    void genericOptimisticConflictUsesGenericConflictCode()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/test/errors/optimistic"
                        )
                )
                .andExpect(
                        status().isConflict()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "CONFLICT"
                                )
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        containsString(
                                                "Обновите данные"
                                        )
                                )
                );
    }

    @Test
    void validationAlwaysReturnsFieldErrorsObject()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/test/errors/validation"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "name": ""
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "VALIDATION_ERROR"
                                )
                )
                .andExpect(
                        jsonPath("$.fieldErrors")
                                .isMap()
                )
                .andExpect(
                        jsonPath("$.fieldErrors.name")
                                .isArray()
                );
    }

    @Test
    void unexpectedErrorDoesNotLeakInternalMessage()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/test/errors/unexpected"
                        )
                )
                .andExpect(
                        status().isInternalServerError()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "INTERNAL_SERVER_ERROR"
                                )
                )
                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "database-password"
                                        )
                                )
                        )
                )
                .andExpect(
                        jsonPath("$.fieldErrors")
                                .isMap()
                );
    }

    @RestController
    static class ErrorProbeController {

        @GetMapping(
                "/test/errors/method-contract"
        )
        Map<String, Boolean> methodContract() {
            return Map.of(
                    "ok",
                    true
            );
        }

        @GetMapping(
                "/test/errors/rate-limit"
        )
        void rateLimit() {
            throw new RateLimitExceededException(
                    "Превышен лимит",
                    Duration.ofMillis(1501)
            );
        }

        @GetMapping(
                "/test/errors/user-version-conflict"
        )
        void userVersionConflict() {
            throw new UserVersionConflictException(
                    USER_ID,
                    5L,
                    6L
            );
        }

        @GetMapping(
                "/test/errors/organization-version-conflict"
        )
        void organizationVersionConflict() {
            throw new OrganizationVersionConflictException(
                    ORGANIZATION_ID,
                    7L,
                    8L
            );
        }

        @GetMapping(
                "/test/errors/optimistic"
        )
        void optimistic() {
            throw new OptimisticLockingFailureException(
                    "stale row"
            );
        }

        @PostMapping(
                "/test/errors/validation"
        )
        String validation(
                @Valid
                @RequestBody
                ValidationRequest request
        ) {
            return request.name();
        }

        @GetMapping(
                "/test/errors/unexpected"
        )
        void unexpected() {
            throw new RuntimeException(
                    "database-password=secret"
            );
        }
    }

    record ValidationRequest(
            @NotBlank
            String name
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            );
        }
    }
}
