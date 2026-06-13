package ru.safeai.gateway.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.auth.dto.AuthUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.dto.LoginResponse;
import ru.safeai.gateway.auth.mapper.AuthUserMapper;
import ru.safeai.gateway.auth.service.AuthService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthUserMapper authUserMapper;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public AuthUserResponse me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SafeAiUserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не авторизован");
        }

        return authUserMapper.toResponse(principal);
    }
}