package ru.safeai.gateway.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiErrorResponseFactoryTest {

    private static final Instant FIXED_TIME =
            Instant.parse(
                    "2026-08-12T12:00:00Z"
            );

    private final ApiErrorResponseFactory factory =
            new ApiErrorResponseFactory(
                    Clock.fixed(
                            FIXED_TIME,
                            ZoneOffset.UTC
                    )
            );

    @Test
    void createBuildsStableImmutableResponse() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRequestURI(
                "/api/auth/login"
        );

        request.setAttribute(
                RequestIdFilter
                        .REQUEST_ID_ATTRIBUTE,
                "request-123"
        );

        ApiErrorResponse response =
                factory.create(
                        HttpStatus.BAD_REQUEST,
                        ApiErrorCode.VALIDATION_ERROR,
                        "Ошибка валидации запроса",
                        request,
                        Map.of(
                                "email",
                                List.of(
                                        " Некорректный email ",
                                        "Некорректный email"
                                )
                        )
                );

        assertThat(response.timestamp())
                .isEqualTo(FIXED_TIME);

        assertThat(response.status())
                .isEqualTo(400);

        assertThat(response.error())
                .isEqualTo(
                        "VALIDATION_ERROR"
                );

        assertThat(response.path())
                .isEqualTo(
                        "/api/auth/login"
                );

        assertThat(response.requestId())
                .isEqualTo(
                        "request-123"
                );

        assertThat(
                response.fieldErrors()
                        .get("email")
        ).containsExactly(
                "Некорректный email"
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
    }

    @Test
    void createUsesEmptyMapAndNullRequestIdWhenTheyAreAbsent() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRequestURI(
                "/api/chats"
        );

        ApiErrorResponse response =
                factory.create(
                        HttpStatus.NOT_FOUND,
                        ApiErrorCode.NOT_FOUND,
                        "Ресурс не найден",
                        request,
                        null
                );

        assertThat(
                response.requestId()
        ).isNull();

        assertThat(
                response.fieldErrors()
        ).isEmpty();
    }

    @Test
    void createRejectsBlankMessage() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRequestURI(
                "/api/chats"
        );

        assertThatThrownBy(() ->
                factory.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ApiErrorCode.INTERNAL_SERVER_ERROR,
                        "   ",
                        request,
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
