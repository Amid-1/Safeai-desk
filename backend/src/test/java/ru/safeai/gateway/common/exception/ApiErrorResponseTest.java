package ru.safeai.gateway.common.exception;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiErrorResponseTest {

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-12T12:00:00Z"
            );

    @Test
    void nullFieldErrorsBecomeEmptyObject() {
        ApiErrorResponse response =
                new ApiErrorResponse(
                        NOW,
                        400,
                        "BAD_REQUEST",
                        "Некорректный запрос",
                        "/api/test",
                        "request-1",
                        null
                );

        assertThat(
                response.fieldErrors()
        ).isEmpty();
    }

    @Test
    void fieldErrorsAreTrimmedDeduplicatedAndDeeplyImmutable() {
        ArrayList<String> messages =
                new ArrayList<>();

        messages.add(
                " invalid "
        );
        messages.add(
                "invalid"
        );

        Map<String, List<String>> source =
                new LinkedHashMap<>();

        source.put(
                " email ",
                messages
        );

        ApiErrorResponse response =
                new ApiErrorResponse(
                        NOW,
                        400,
                        "VALIDATION_ERROR",
                        "Ошибка валидации",
                        "/api/test",
                        "request-1",
                        source
                );

        messages.clear();
        source.clear();

        assertThat(
                response.fieldErrors()
                        .get("email")
        ).containsExactly(
                "invalid"
        );

        assertThatThrownBy(() ->
                response.fieldErrors()
                        .put(
                                "password",
                                List.of("invalid")
                        )
        ).isInstanceOf(
                UnsupportedOperationException.class
        );

        assertThatThrownBy(() ->
                response.fieldErrors()
                        .get("email")
                        .add("other")
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

    @Test
    void blankMessageIsRejected() {
        assertThatThrownBy(() ->
                new ApiErrorResponse(
                        NOW,
                        500,
                        "INTERNAL_SERVER_ERROR",
                        " ",
                        "/api/test",
                        null,
                        null
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "message must not be blank"
                );
    }
}
