package ru.safeai.gateway.audit.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.audit.dto.AuditEventCursorResponse;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.service.AuditEventCursorService;
import ru.safeai.gateway.audit.service.AuditEventQueryService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit-events")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AuditController {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AuditEventQueryService queryService;
    private final AuditEventCursorService cursorService;

    @GetMapping
    public Page<AuditEventResponse> findAll(
            @AuthenticationPrincipal
            SafeAiUserPrincipal currentUser,

            @ModelAttribute
            AuditEventFilter filter,

            @PageableDefault(
                    size = DEFAULT_PAGE_SIZE,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        return queryService.findAll(
                currentUser,
                filter,
                pageable
        );
    }

    @GetMapping("/cursor")
    public AuditEventCursorResponse findAllByCursor(
            @AuthenticationPrincipal
            SafeAiUserPrincipal currentUser,

            @ModelAttribute
            AuditEventFilter filter,

            @RequestParam(required = false)
            String cursor,

            @RequestParam(required = false)
            Integer limit
    ) {
        return cursorService.findAll(
                currentUser,
                filter,
                cursor,
                limit
        );
    }

    @GetMapping("/users/{userId}")
    public Page<AuditEventResponse> findByUserId(
            @PathVariable
            UUID userId,

            @AuthenticationPrincipal
            SafeAiUserPrincipal currentUser,

            @PageableDefault(
                    size = DEFAULT_PAGE_SIZE,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        return queryService.findByUserId(
                userId,
                currentUser,
                pageable
        );
    }
}
