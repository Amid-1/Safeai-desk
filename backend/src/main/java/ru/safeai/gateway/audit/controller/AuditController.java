package ru.safeai.gateway.audit.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.audit.dto.AuditEventCursorResponse;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventPageResponse;
import ru.safeai.gateway.audit.service.AuditEventCursorService;
import ru.safeai.gateway.audit.service.AuditEventQueryService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit-events")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AuditController {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AuditEventQueryService queryService;
    private final AuditEventCursorService cursorService;

    @GetMapping
    public AuditEventPageResponse findAll(
            @AuthenticationPrincipal(
                    errorOnInvalidType = true
            )
            SafeAiUserPrincipal currentUser,

            @Valid
            @ModelAttribute
            AuditEventFilter filter,

            @PageableDefault(
                    size = DEFAULT_PAGE_SIZE,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        return AuditEventPageResponse.from(
                queryService.findAll(
                        currentUser,
                        filter,
                        pageable
                )
        );
    }

    @GetMapping("/cursor")
    public AuditEventCursorResponse findAllByCursor(
            @AuthenticationPrincipal(
                    errorOnInvalidType = true
            )
            SafeAiUserPrincipal currentUser,

            @Valid
            @ModelAttribute
            AuditEventFilter filter,

            @RequestParam(required = false)
            @Size(max = 512)
            String cursor,

            @RequestParam(required = false)
            @Min(1)
            @Max(100)
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
    public AuditEventPageResponse findByUserId(
            @PathVariable
            UUID userId,

            @AuthenticationPrincipal(
                    errorOnInvalidType = true
            )
            SafeAiUserPrincipal currentUser,

            @PageableDefault(
                    size = DEFAULT_PAGE_SIZE,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        return AuditEventPageResponse.from(
                queryService.findByUserId(
                        userId,
                        currentUser,
                        pageable
                )
        );
    }
}
