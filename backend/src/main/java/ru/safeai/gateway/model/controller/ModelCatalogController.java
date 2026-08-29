package ru.safeai.gateway.model.controller;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.CreateModelCatalogVersionRequest;
import ru.safeai.gateway.model.dto.ModelCatalogEntryResponse;
import ru.safeai.gateway.model.service.ModelCatalogService;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin/models/catalog")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ModelCatalogController {

    private final ModelCatalogService service;

    public ModelCatalogController(
            ModelCatalogService service
    ) {
        this.service = Objects.requireNonNull(
                service,
                "service не должен быть null"
        );
    }

    @GetMapping
    public List<ModelCatalogEntryResponse> latest(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.findLatest(currentUser);
    }

    @GetMapping("/effective")
    public List<ModelCatalogEntryResponse> effective(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.findEffective(currentUser);
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ModelCatalogEntryResponse createVersion(
            @Valid @RequestBody CreateModelCatalogVersionRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.createVersion(request, currentUser);
    }

    @PostMapping("/import-runtime")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ModelCatalogEntryResponse importRuntime(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.importRuntime(currentUser);
    }
}
