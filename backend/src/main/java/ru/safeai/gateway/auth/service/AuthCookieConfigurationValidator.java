package ru.safeai.gateway.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.JwtProperties;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AuthCookieConfigurationValidator {

    private final AuthCookieProperties authCookieProperties;
    private final JwtProperties jwtProperties;

    @PostConstruct
    void validate() {
        Duration jwtLifetime = Duration.ofMinutes(
                jwtProperties.expirationMinutes()
        );

        if (!authCookieProperties.accessTokenMaxAge().equals(jwtLifetime)) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.access-token-max-age должен совпадать "
                            + "с app.security.jwt.expiration-minutes"
            );
        }
    }
}
