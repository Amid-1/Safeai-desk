package ru.safeai.gateway.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.PermanentDeleteUserRequest;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.dto.UserDetailsResponse;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.dto.UserStatisticsResponse;
import ru.safeai.gateway.user.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UserController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return userService.create(request, currentUser);
    }

    @GetMapping
    public Page<UserResponse> findAll(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser,
            @RequestParam(required = false) String role,
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        return userService.findAll(currentUser, role, pageable);
    }

    @GetMapping("/statistics")
    public UserStatisticsResponse statistics(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return userService.statistics(currentUser);
    }

    @GetMapping("/{id}")
    public UserDetailsResponse findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return userService.findDetailsById(id, currentUser);
    }

    @PatchMapping("/{id}")
    public UserResponse updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return userService.updateUser(id, request, currentUser);
    }

    @PatchMapping("/{id}/enabled")
    public UserResponse updateEnabled(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserEnabledRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return userService.updateEnabled(id, request, currentUser);
    }

    @PatchMapping("/{id}/roles")
    public UserResponse updateRoles(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRolesRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        return userService.updateRoles(id, request, currentUser);
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetUserPasswordRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        userService.resetPassword(id, request, currentUser);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/{id}/permanent-deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permanentlyDelete(
            @PathVariable UUID id,
            @Valid @RequestBody PermanentDeleteUserRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal currentUser
    ) {
        userService.permanentlyDelete(id, request, currentUser);
    }
}
