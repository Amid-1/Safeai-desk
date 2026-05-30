package ru.safeai.gateway.auth.dto;

public record LoginResponse(
        String token,
        String tokenType,
        AuthUserResponse user
) {
}