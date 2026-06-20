package ru.safeai.gateway.audit.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.service.AuditEventQueryService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit-events")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AuditController {

    private final AuditEventQueryService auditEventQueryService;

    @GetMapping
    public Page<AuditEventResponse> findAll(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser,
            @PageableDefault(
                    size = 50,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        return auditEventQueryService.findAll(currentUser, pageable);
    }

    @GetMapping("/users/{userId}")
    public Page<AuditEventResponse> findByUserId(
            @PathVariable UUID userId,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser,
            @PageableDefault(
                    size = 50,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        return auditEventQueryService.findByUserId(userId, currentUser, pageable);
    }
}