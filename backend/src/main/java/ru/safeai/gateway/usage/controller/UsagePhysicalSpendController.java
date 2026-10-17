package ru.safeai.gateway.usage.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsagePhysicalSpendResponse;
import ru.safeai.gateway.usage.dto.UsagePhysicalReconciliationResponse;
import ru.safeai.gateway.usage.service.UsagePhysicalSpendService;

import java.time.Instant;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin/usage/physical-attempts")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UsagePhysicalSpendController {
    private final UsagePhysicalSpendService service;

    public UsagePhysicalSpendController(UsagePhysicalSpendService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping
    public UsagePhysicalSpendResponse coverage(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.findCoverage(dateFrom, dateTo, currentUser);
    }

    @GetMapping("/reconciliation")
    public UsagePhysicalReconciliationResponse reconciliation(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return service.reconcile(dateFrom, dateTo, currentUser);
    }

}
