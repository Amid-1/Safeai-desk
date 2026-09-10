package ru.safeai.gateway.model.controller;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.OrganizationModelPolicyResponse;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;
import ru.safeai.gateway.model.service.OrganizationModelPolicyService;
import ru.safeai.gateway.model.service.ModelPolicyPreviewService;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/models/policies")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class OrganizationModelPolicyController {

    private final OrganizationModelPolicyService service;
    private final ModelPolicyPreviewService previewService;

    public OrganizationModelPolicyController(
            OrganizationModelPolicyService service,
            ModelPolicyPreviewService previewService
    ) {
        this.service = Objects.requireNonNull(
                service,
                "service не должен быть null"
        );
        this.previewService = Objects.requireNonNull(
                previewService,
                "previewService не должен быть null"
        );
    }

    @GetMapping("/{organizationId}")
    public OrganizationModelPolicyResponse current(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.current(organizationId, currentUser);
    }

    @PostMapping("/{organizationId}")
    public OrganizationModelPolicyResponse createVersion(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateOrganizationModelPolicyVersionRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.createVersion(organizationId, request, currentUser);
    }

    @PostMapping("/{organizationId}/preview")
    public ModelPolicyPreviewResponse preview(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateOrganizationModelPolicyVersionRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return previewService.preview(organizationId, request, currentUser);
    }
}
