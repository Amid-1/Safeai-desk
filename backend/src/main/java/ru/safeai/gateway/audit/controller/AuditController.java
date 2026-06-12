package ru.safeai.gateway.audit.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.service.AuditEventQueryService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit-events")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditEventQueryService auditEventQueryService;

    @GetMapping
    public List<AuditEventResponse> findAll() {
        return auditEventQueryService.findAll();
    }

    @GetMapping("/users/{userId}")
    public List<AuditEventResponse> findByUserId(@PathVariable UUID userId) {
        return auditEventQueryService.findByUserId(userId);
    }
}