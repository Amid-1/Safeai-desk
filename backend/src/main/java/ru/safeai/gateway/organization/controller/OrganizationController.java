package ru.safeai.gateway.organization.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.service.OrganizationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request) {
        return organizationService.create(request);
    }

    @GetMapping
    public List<OrganizationResponse> findAll() {
        return organizationService.findAll();
    }

    @GetMapping("/{id}")
    public OrganizationResponse findById(@PathVariable UUID id) {
        return organizationService.findById(id);
    }
}