package ru.safeai.gateway.common.exception;

import java.util.UUID;

public final class UserVersionConflictException
        extends ConflictException {

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
    }
}
