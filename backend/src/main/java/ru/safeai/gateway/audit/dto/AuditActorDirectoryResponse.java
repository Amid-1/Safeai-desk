
package ru.safeai.gateway.audit.dto;

import java.util.UUID;

public record AuditActorDirectoryResponse(
        UUID actorUserId,
        UUID actorOrganizationId,
        String actorEmail,
        String actorDisplayName
) {
}
