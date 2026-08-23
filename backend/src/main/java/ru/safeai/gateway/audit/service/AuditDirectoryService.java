package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.dto.AuditActorDirectoryResponse;
import ru.safeai.gateway.audit.dto.AuditTargetOrganizationDirectoryResponse;
import ru.safeai.gateway.audit.repository.AuditDirectoryQueryRepository;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditDirectoryService {

    private static final int MAX_DIRECTORY_LIMIT = 50;
    private static final int MAX_ORGANIZATION_QUERY_LENGTH = 255;
    private static final int MAX_ACTOR_QUERY_LENGTH = 320;

    private final AuditDirectoryQueryRepository repository;

    public List<String> findEventTypes() {
        return Arrays.stream(
                        AuditEventType.values()
                )
                .map(Enum::name)
                .sorted()
                .toList();
    }

    public List<AuditTargetOrganizationDirectoryResponse>
    findTargetOrganizations(
            SafeAiUserPrincipal currentUser,
            String query,
            int limit
    ) {
        requireAuditReader(currentUser);

        if (!isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Каталог целевых организаций аудита "
                            + "доступен только SUPER_ADMIN"
            );
        }

        return repository.findTargetOrganizations(
                normalizeQuery(
                        query,
                        MAX_ORGANIZATION_QUERY_LENGTH
                ),
                validateLimit(limit)
        );
    }

    public List<AuditActorDirectoryResponse> findActors(
            SafeAiUserPrincipal currentUser,
            String query,
            UUID requestedTargetOrganizationId,
            int limit
    ) {
        requireAuditReader(currentUser);

        UUID enforcedTargetOrganizationId =
                resolveTargetOrganizationId(
                        currentUser,
                        requestedTargetOrganizationId
                );

        return repository.findActors(
                enforcedTargetOrganizationId,
                normalizeQuery(
                        query,
                        MAX_ACTOR_QUERY_LENGTH
                ),
                validateLimit(limit)
        );
    }

    private UUID resolveTargetOrganizationId(
            SafeAiUserPrincipal currentUser,
            UUID requestedTargetOrganizationId
    ) {
        if (isSuperAdmin(currentUser)) {
            return requestedTargetOrganizationId;
        }

        UUID ownOrganizationId =
                Objects.requireNonNull(
                        currentUser.getOrganizationId(),
                        "organizationId текущего ADMIN "
                                + "не должен быть null"
                );

        if (requestedTargetOrganizationId != null
                && !ownOrganizationId.equals(
                requestedTargetOrganizationId
        )) {
            throw new ForbiddenOperationException(
                    "Нельзя читать каталог инициаторов "
                            + "другой организации"
            );
        }

        return ownOrganizationId;
    }

    private String normalizeQuery(
            String query,
            int maxLength
    ) {
        if (query == null || query.isBlank()) {
            return null;
        }

        String normalized = query
                .trim()
                .toLowerCase(Locale.ROOT);

        if (normalized.length() > maxLength) {
            throw new BadRequestException(
                    "Строка поиска должна быть не длиннее "
                            + maxLength
                            + " символов"
            );
        }

        return normalized;
    }

    private int validateLimit(int limit) {
        if (limit < 1 || limit > MAX_DIRECTORY_LIMIT) {
            throw new BadRequestException(
                    "limit должен быть в диапазоне 1–"
                            + MAX_DIRECTORY_LIMIT
            );
        }

        return limit;
    }

    private void requireAuditReader(
            SafeAiUserPrincipal currentUser
    ) {
        requirePrincipal(currentUser);

        if (!isAdmin(currentUser)
                && !isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Аудит доступен только ADMIN или SUPER_ADMIN"
            );
        }
    }

    private void requirePrincipal(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
    }

    private boolean isAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return hasAuthority(
                currentUser,
                SystemRole.ADMIN
        );
    }

    private boolean isSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return hasAuthority(
                currentUser,
                SystemRole.SUPER_ADMIN
        );
    }

    private boolean hasAuthority(
            SafeAiUserPrincipal currentUser,
            SystemRole role
    ) {
        return currentUser
                .authorityNames()
                .contains(role.authority());
    }
}
