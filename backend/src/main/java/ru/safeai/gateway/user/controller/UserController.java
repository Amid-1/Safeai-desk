package ru.safeai.gateway.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;

import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.service.UserService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;

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
    public Page<UserResponse> findAll(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser,
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        return userService.findAll(currentUser, pageable);
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetUserPasswordRequest request,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        userService.resetPassword(id, request, currentUser);
    }
}