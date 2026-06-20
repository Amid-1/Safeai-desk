package ru.safeai.gateway.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.service.UserService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UserController {

    private final UserService userService;

    @PostMapping
    public UserResponse create(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return userService.create(request, currentUser);
    }

    @GetMapping
    public List<UserResponse> findAll(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return userService.findAll(currentUser);
    }

    @GetMapping("/{id}")
    public UserResponse findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return userService.findById(id, currentUser);
    }

    @PatchMapping("/{id}/enabled")
    public UserResponse updateEnabled(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserEnabledRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return userService.updateEnabled(id, request, currentUser);
    }

    @PatchMapping("/{id}/roles")
    public UserResponse updateRoles(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRolesRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return userService.updateRoles(id, request, currentUser);
    }

    @PostMapping("/{id}/reset-password")
    public UserResponse resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetUserPasswordRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return userService.resetPassword(id, request, currentUser);
    }
}