package ru.safeai.gateway.model.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.ModelRouteDecisionResponse;
import ru.safeai.gateway.model.service.ModelRoutingService;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/models/route-decisions")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ModelRouteDecisionController {

    private final ModelRoutingService service;

    public ModelRouteDecisionController(
            ModelRoutingService service
    ) {
        this.service = Objects.requireNonNull(
                service,
                "service не должен быть null"
        );
    }

    @GetMapping("/{decisionId}")
    public ModelRouteDecisionResponse findById(
            @PathVariable UUID decisionId,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.findDecision(decisionId, currentUser);
    }
}
