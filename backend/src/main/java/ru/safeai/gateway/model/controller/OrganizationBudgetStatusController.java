package ru.safeai.gateway.model.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.OrganizationBudgetStatusResponse;
import ru.safeai.gateway.model.dto.PlatformBudgetSummaryResponse;
import ru.safeai.gateway.model.service.OrganizationBudgetStatusService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/models/policies")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class OrganizationBudgetStatusController {
    private final OrganizationBudgetStatusService service;
    private final JsonMapper mapper;

    public OrganizationBudgetStatusController(OrganizationBudgetStatusService service, JsonMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/budget-summary")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PlatformBudgetSummaryResponse platformSummary(
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal currentUser
    ) {
        return service.platformSummary(currentUser);
    }

    @GetMapping("/{organizationId}/budget-status")
    public ObjectNode current(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal currentUser
    ) {
        OrganizationBudgetStatusResponse snapshot = service.current(organizationId, currentUser);
        ObjectNode response = mapper.valueToTree(snapshot);
        // These are required nullable fields of the wire contract, not optional
        // properties. Restore explicit nulls even with NON_NULL bean inclusion.
        if (snapshot.monthlyBudgetUsd() == null) {
            response.putNull("monthlyBudgetUsd");
        }
        if (snapshot.remainingUsd() == null) {
            response.putNull("remainingUsd");
        }
        return response;
    }
}
