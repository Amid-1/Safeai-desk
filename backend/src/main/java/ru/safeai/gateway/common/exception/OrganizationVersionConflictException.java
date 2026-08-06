package ru.safeai.gateway.common.exception;

import java.util.UUID;

public final class OrganizationVersionConflictException
        extends ConflictException {

    public OrganizationVersionConflictException(
            UUID organizationId,
            long expectedVersion,
            long actualVersion
    ) {
        super(
                ApiErrorCode.ORGANIZATION_VERSION_CONFLICT,
                "Организация была изменена другим пользователем. "
                        + "Обновите данные и повторите операцию",
                null
        );
    }
}
