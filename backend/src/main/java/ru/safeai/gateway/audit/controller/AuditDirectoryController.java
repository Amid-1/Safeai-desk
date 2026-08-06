
package ru.safeai.gateway.audit.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.audit.dto.AuditActorDirectoryResponse;
import ru.safeai.gateway.audit.dto.AuditTargetOrganizationDirectoryResponse;
import ru.safeai.gateway.audit.service.AuditDirectoryService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AuditDirectoryController {


    private final AuditDirectoryService directoryService;

    @GetMapping("/audit-event-types")
    public List<String> findEventTypes() {
        return directoryService.findEventTypes();
    }

    @GetMapping("/audit-organizations")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<AuditTargetOrganizationDirectoryResponse>
    findTargetOrganizations(
            @AuthenticationPrincipal(
                    errorOnInvalidType = true
            )
            SafeAiUserPrincipal currentUser,

            @RequestParam(required = false)
            @Size(max = 255)
            String query,

            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(50)
            int limit
    ) {
        return directoryService.findTargetOrganizations(
                currentUser,
                query,
                limit
        );
    }

    @GetMapping("/audit-actors")
    public List<AuditActorDirectoryResponse> findActors(
            @AuthenticationPrincipal(
                    errorOnInvalidType = true
            )
            SafeAiUserPrincipal currentUser,

            @RequestParam(required = false)
            @Size(max = 320)
            String query,

            @RequestParam(required = false)
            UUID organizationId,

            @RequestParam(defaultValue = "20")
            @Min(1)
            @Max(50)
            int limit
    ) {
        return directoryService.findActors(
                currentUser,
                query,
                organizationId,
                limit
        );
    }
}
