package ru.safeai.gateway.audit.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.audit.dto.AuditIntegrityStatus;
import ru.safeai.gateway.audit.service.AuditIntegrityService;

@RestController
@RequestMapping("/api/admin/audit-events/integrity")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AuditIntegrityController {
    private final AuditIntegrityService service;

    public AuditIntegrityController(AuditIntegrityService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AuditIntegrityStatus> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current());
    }
}
