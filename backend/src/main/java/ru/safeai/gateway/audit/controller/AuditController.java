package ru.safeai.gateway.audit.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.service.AuditEventQueryService;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit-events")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditEventQueryService auditEventQueryService;

    @GetMapping
    public Page<AuditEventResponse> findAll(Pageable pageable) {
        return auditEventQueryService.findAll(pageable);
    }

    @GetMapping("/users/{userId}")
    public Page<AuditEventResponse> findByUserId(
            @PathVariable UUID userId,
            Pageable pageable
    ) {
        return auditEventQueryService.findByUserId(userId, pageable);
    }
}