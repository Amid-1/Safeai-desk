package ru.safeai.gateway.model.controller;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    private static final int DEFAULT_PAGE_LIMIT = 100;
    private static final int DEFAULT_HISTORY_LIMIT = 100;

    private final ModelCatalogService service;

    public ModelCatalogController(ModelCatalogService service) {
        this.service = Objects.requireNonNull(
                service,
                "service не должен быть null"
        );
    }

    /**
     * Bounded latest-created catalog page. The cursor is the last modelKey from
     * the previous page; null starts from the beginning.
     */
    @GetMapping
    public List<ModelCatalogEntryResponse> latest(
            @RequestParam(value = "afterModelKey", required = false) String afterModelKey,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.findLatest(
                afterModelKey,
                limit,
                currentUser
        );
    }

    /**
     * Source-compatible Java helper for pre-pagination callers/tests.
     * Deliberately not mapped as a second Spring MVC endpoint.
     */
    public List<ModelCatalogEntryResponse> latest(
            SafeAiUserPrincipal currentUser
    ) {
        return latest(
                null,
                DEFAULT_PAGE_LIMIT,
                currentUser
        );
    }

    /** Bounded server-clock-effective catalog page. */
    @GetMapping("/effective")
    public List<ModelCatalogEntryResponse> effective(
            @RequestParam(value = "afterModelKey", required = false) String afterModelKey,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.findEffective(
                afterModelKey,
                limit,
                currentUser
        );
    }

    /**
     * Source-compatible Java helper for pre-pagination callers/tests.
     * Deliberately not mapped as a second Spring MVC endpoint.
     */
    public List<ModelCatalogEntryResponse> effective(
            SafeAiUserPrincipal currentUser
    ) {
        return effective(
                null,
                DEFAULT_PAGE_LIMIT,
                currentUser
        );
    }

    @GetMapping("/versions")
    public List<ModelCatalogEntryResponse> history(
            @RequestParam("modelKey") String modelKey,
            @RequestParam(value = "beforeVersion", required = false) Integer beforeVersion,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.findHistory(
                modelKey,
                beforeVersion,
                limit,
                currentUser
        );
    }

    /**
     * Source-compatible Java helper for callers/tests that used the original
     * two-argument history method. Deliberately not a Spring MVC mapping.
     */
    public List<ModelCatalogEntryResponse> history(
            String modelKey,
            SafeAiUserPrincipal currentUser
    ) {
        return history(
                modelKey,
                null,
                DEFAULT_HISTORY_LIMIT,
                currentUser
        );
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ModelCatalogEntryResponse createVersion(
            @Valid @RequestBody CreateModelCatalogVersionRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.createVersion(
                request,
                currentUser
        );
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
