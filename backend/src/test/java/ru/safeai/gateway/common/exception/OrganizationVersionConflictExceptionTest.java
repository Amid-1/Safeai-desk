package ru.safeai.gateway.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationVersionConflictExceptionTest {

    @Test
    void exposesStableConflictContract() {
        UUID organizationId =
                UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                );

        OrganizationVersionConflictException exception =
                new OrganizationVersionConflictException(
                        organizationId,
                        3L,
                        4L
                );

        assertThat(exception.getStatus())
                .isEqualTo(
                        HttpStatus.CONFLICT
                );

        assertThat(exception.getErrorCode())
                .isEqualTo(
                        ApiErrorCode
                                .ORGANIZATION_VERSION_CONFLICT
                );

        assertThat(exception.getOrganizationId())
                .isEqualTo(
                        organizationId
                );

        assertThat(exception.getExpectedVersion())
                .isEqualTo(3L);

        assertThat(exception.getActualVersion())
                .isEqualTo(4L);
    }
}
