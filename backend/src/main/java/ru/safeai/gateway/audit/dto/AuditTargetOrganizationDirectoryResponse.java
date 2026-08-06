
package ru.safeai.gateway.audit.dto;

import java.util.UUID;

public record AuditTargetOrganizationDirectoryResponse(
        UUID targetOrganizationId,
        String targetOrganizationName
) {
}
