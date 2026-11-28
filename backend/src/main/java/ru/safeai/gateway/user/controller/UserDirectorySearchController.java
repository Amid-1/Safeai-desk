package ru.safeai.gateway.user.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.service.UserDirectorySearchService;
import java.util.UUID;

/** Keeps the existing /api/users read and mutation contracts unchanged. */
@RestController
@RequestMapping("/api/users/search")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UserDirectorySearchController {

    private final UserDirectorySearchService service;

    @GetMapping
    public Page<UserResponse> search(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) UUID organizationId
    ) {
        return organizationId == null
                ? service.search(currentUser, role, enabled, query, page, size)
                : service.search(currentUser, role, enabled, query, page, size, organizationId);
    }

    @GetMapping("/statistics")
    public UserDirectorySearchService.DirectoryStatistics statistics(
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal currentUser,
            @RequestParam(required = false) UUID organizationId
    ) {
        return service.statistics(currentUser, organizationId);
    }
}
