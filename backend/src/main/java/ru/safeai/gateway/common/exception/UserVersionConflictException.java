package ru.safeai.gateway.common.exception;

import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

@Getter
public final class UserVersionConflictException
        extends ConflictException {

    private final UUID userId;
    private final long expectedVersion;
    private final long actualVersion;

    public UserVersionConflictException(
            UUID userId,
            long expectedVersion,
            long actualVersion
    ) {
        super(
                ApiErrorCode.USER_VERSION_CONFLICT,
                "Пользователь был изменён другим администратором. "
                        + "Обновите данные и повторите операцию",
                null
        );

        this.userId =
                Objects.requireNonNull(
                        userId,
                        "userId не должен быть null"
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

        this.expectedVersion =
                expectedVersion;

        this.actualVersion =
                actualVersion;
    }
}
