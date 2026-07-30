package ru.safeai.gateway.audit.service;

import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;

import java.util.Objects;
import java.util.UUID;

public final class AuditEventQueryPolicy {

    private AuditEventQueryPolicy() {
    }

    public static QueryScope resolve(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        AuditEventFilter effectiveFilter =
                filter == null
                        ? emptyFilter()
                        : filter;

        validateDateRange(effectiveFilter);

        boolean superAdmin = currentUser
                .authorityNames()
                .contains(
                        SystemRole.SUPER_ADMIN.authority()
                );

        validateOrganizationFilter(
                currentUser,
                effectiveFilter,
                superAdmin
        );

        UUID organizationId = superAdmin
                ? effectiveFilter.organizationId()
                : currentUser.getOrganizationId();

        return new QueryScope(
                effectiveFilter,
                organizationId
        );
    }

    private static void validateOrganizationFilter(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            boolean superAdmin
    ) {
        if (superAdmin
                || filter.organizationId() == null) {
            return;
        }

        if (!filter.organizationId().equals(
                currentUser.getOrganizationId()
        )) {
            throw new ForbiddenOperationException(
                    "Нельзя фильтровать аудит другой организации"
            );
        }
    }

    private static void validateDateRange(
            AuditEventFilter filter
    ) {
        if (filter.dateFrom() != null
                && filter.dateTo() != null
                && !filter.dateFrom().isBefore(
                        filter.dateTo()
                )) {
            throw new BadRequestException(
                    "dateFrom должен быть раньше dateTo"
            );
        }
    }

    private static AuditEventFilter emptyFilter() {
        return new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public record QueryScope(
            AuditEventFilter filter,
            UUID organizationId
    ) {
        public QueryScope {
            Objects.requireNonNull(
                    filter,
                    "filter не должен быть null"
            );
        }
    }
}
