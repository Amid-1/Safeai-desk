package ru.safeai.gateway.model.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.OrganizationBudgetStatusResponse;
import ru.safeai.gateway.model.service.OrganizationBudgetStatusService;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/models/policies")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class OrganizationBudgetStatusController {
    private final OrganizationBudgetStatusService service;

    public OrganizationBudgetStatusController(OrganizationBudgetStatusService service) {
        this.service = service;
    }

    @GetMapping("/{organizationId}/budget-status")
    public OrganizationBudgetStatusResponse current(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal currentUser
    ) {
        return service.current(organizationId, currentUser);
    }
}
