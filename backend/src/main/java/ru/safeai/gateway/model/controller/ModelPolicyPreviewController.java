package ru.safeai.gateway.model.controller;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;
import ru.safeai.gateway.model.service.ModelPolicyPreviewService;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/models/policies")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ModelPolicyPreviewController {

    private final ModelPolicyPreviewService service;

    public ModelPolicyPreviewController(
            ModelPolicyPreviewService service
    ) {
        this.service = Objects.requireNonNull(
                service,
                "service"
        );
    }

    @PostMapping("/{organizationId}/preview")
    public ModelPolicyPreviewResponse preview(
            @PathVariable UUID organizationId,
            @Valid @RequestBody
            CreateOrganizationModelPolicyVersionRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.preview(
                organizationId,
                request,
                currentUser
        );
    }
}