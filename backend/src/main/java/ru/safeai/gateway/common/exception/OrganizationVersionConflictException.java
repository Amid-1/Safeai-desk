package ru.safeai.gateway.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Objects;
import java.util.UUID;

@Getter
public final class OrganizationVersionConflictException
        extends ApiException {

    private final UUID organizationId;
    private final long expectedVersion;
    private final long actualVersion;

    public OrganizationVersionConflictException(
            UUID organizationId,
            long expectedVersion,
            long actualVersion
    ) {
        super(
                HttpStatus.CONFLICT,
                ApiErrorCode.ORGANIZATION_VERSION_CONFLICT,
                "Организация была изменена другим пользователем. "
                        + "Обновите данные и повторите операцию",
                null
        );

        this.organizationId =
                Objects.requireNonNull(
                        organizationId,
                        "organizationId не должен быть null"
                );

        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "expectedVersion не может быть отрицательной"
            );
        }

        if (actualVersion < 0L) {
            throw new IllegalArgumentException(
                    "actualVersion не может быть отрицательной"
            );
        }

        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }
}
