package ru.safeai.gateway.usage.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsagePhysicalSpendResponse;
import ru.safeai.gateway.usage.dto.UsagePhysicalReconciliationResponse;
import ru.safeai.gateway.usage.dto.UsagePhysicalReportResponse;
import ru.safeai.gateway.usage.service.UsagePhysicalSpendService;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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

    @GetMapping("/report")
    public ResponseEntity<UsagePhysicalReportResponse> report(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @RequestParam(required = false) UUID organizationId,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.report(dateFrom, dateTo, organizationId, currentUser));
    }

    @GetMapping("/providers")
    public ResponseEntity<ru.safeai.gateway.usage.dto.UsageProviderReportResponse> providers(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) UUID userId,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal currentUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.providerReport(dateFrom, dateTo, organizationId, model, userId, currentUser));
    }
}
