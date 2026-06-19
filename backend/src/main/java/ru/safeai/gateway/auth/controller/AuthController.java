package ru.safeai.gateway.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.dto.LoginResponse;
import ru.safeai.gateway.auth.service.AuthService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        return authService.login(request, httpRequest);
    }

    @GetMapping("/me")
    public CurrentUserResponse me(
            @AuthenticationPrincipal SafeAiUserPrincipal principal
    ) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не авторизован");
        }

        return authService.getCurrentUser(principal);
    }
}