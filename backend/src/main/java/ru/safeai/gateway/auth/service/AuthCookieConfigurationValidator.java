package ru.safeai.gateway.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.JwtProperties;

import java.time.Duration;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AuthCookieConfigurationValidator {

    private final AuthCookieProperties authCookieProperties;
    private final JwtProperties jwtProperties;

    @PostConstruct
    void validate() {
        Duration cookieLifetime =
                Objects.requireNonNull(
                        authCookieProperties.accessTokenMaxAge(),
                        "safeai.auth.cookies.access-token-max-age "
                                + "не должен быть null"
                );

        Duration jwtLifetime =
                Duration.ofMinutes(
                        jwtProperties.expirationMinutes()
                );

        if (!cookieLifetime.equals(jwtLifetime)) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.access-token-max-age "
                            + "должен совпадать с "
                            + "app.security.jwt.expiration-minutes"
                            + "; cookieLifetime="
                            + cookieLifetime
                            + "; jwtLifetime="
                            + jwtLifetime
                            + "; jwtExpirationMinutes="
                            + jwtProperties.expirationMinutes()
            );
        }
    }
}