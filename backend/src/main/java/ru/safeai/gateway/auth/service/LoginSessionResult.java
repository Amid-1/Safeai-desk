package ru.safeai.gateway.auth.service;

import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.common.security.AccessTokenSubject;

import java.time.Duration;

public record LoginSessionResult(
        CurrentUserResponse currentUser,
        AccessTokenSubject accessTokenSubject,
        String rawRefreshToken,
        Duration refreshCookieMaxAge
) {
}
