package ru.safeai.gateway.organization.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.UpdateOrganizationEnabledRequest;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
import ru.safeai.gateway.organization.service.OrganizationService;

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
    public Page<OrganizationResponse> findAll(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser,
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        return organizationService.findAll(currentUser, pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public OrganizationResponse findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return organizationService.findById(id, currentUser);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/{id}")
    public OrganizationResponse updateName(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrganizationRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return organizationService.updateName(id, request, currentUser);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/{id}/enabled")
    public OrganizationResponse updateEnabled(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrganizationEnabledRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return organizationService.updateEnabled(id, request, currentUser);
    }
}