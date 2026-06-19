package ru.safeai.gateway.organization.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.service.OrganizationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping
    public OrganizationResponse create(
            @Valid @RequestBody CreateOrganizationRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return organizationService.create(request, currentUser);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public List<OrganizationResponse> findAll(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return organizationService.findAll(currentUser);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public OrganizationResponse findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return organizationService.findById(id, currentUser);
    }
}