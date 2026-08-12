package ru.safeai.gateway.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserVersionConflictExceptionTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    @Test
    void exposesStableConflictContractAndLogMetadata() {
        UserVersionConflictException exception =
                new UserVersionConflictException(
                        USER_ID,
                        5L,
                        6L
                );

        assertThat(
                exception.getStatus()
        ).isEqualTo(
                HttpStatus.CONFLICT
        );

        assertThat(
                exception.getErrorCode()
        ).isEqualTo(
                ApiErrorCode
                        .USER_VERSION_CONFLICT
        );

        assertThat(
                exception.getUserId()
        ).isEqualTo(USER_ID);

        assertThat(
                exception.getExpectedVersion()
        ).isEqualTo(5L);

        assertThat(
                exception.getActualVersion()
        ).isEqualTo(6L);

        assertThat(
                exception.getPublicMessage()
        ).doesNotContain(
                "5",
                "6"
        );
    }

    @Test
    void negativeVersionsAreRejected() {
        assertThatThrownBy(() ->
                throwUserVersionConflict(
                        -1L,
                        0L
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );

        assertThatThrownBy(() ->
                throwUserVersionConflict(
                        0L,
                        -1L
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }

    private static void throwUserVersionConflict(
            long expectedVersion,
            long actualVersion
    ) {
        throw new UserVersionConflictException(
                USER_ID,
                expectedVersion,
                actualVersion
        );
    }
}
