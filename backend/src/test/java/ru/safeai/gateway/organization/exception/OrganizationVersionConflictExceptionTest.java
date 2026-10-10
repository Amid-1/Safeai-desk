package ru.safeai.gateway.organization.exception;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationVersionConflictExceptionTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    @Test
    void exposesStableConflictContractAndLogMetadata() {
        OrganizationVersionConflictException exception =
                new OrganizationVersionConflictException(
                        ORGANIZATION_ID,
                        3L,
                        4L
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
                        .ORGANIZATION_VERSION_CONFLICT
        );

        assertThat(
                exception.getOrganizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                exception.getExpectedVersion()
        ).isEqualTo(3L);

        assertThat(
                exception.getActualVersion()
        ).isEqualTo(4L);

        assertThat(
                exception.getPublicMessage()
        ).doesNotContain(
                "3",
                "4"
        );
    }

    @Test
    void negativeVersionsAreRejected() {
        assertThatThrownBy(() ->
                throwOrganizationVersionConflict(
                        -1L,
                        0L
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );

        assertThatThrownBy(() ->
                throwOrganizationVersionConflict(
                        0L,
                        -1L
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }

    private static void throwOrganizationVersionConflict(
            long expectedVersion,
            long actualVersion
    ) {
        throw new OrganizationVersionConflictException(
                ORGANIZATION_ID,
                expectedVersion,
                actualVersion
        );
    }
}
