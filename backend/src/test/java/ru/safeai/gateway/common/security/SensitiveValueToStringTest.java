package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.service.LoginSessionResult;
import ru.safeai.gateway.auth.service.RefreshTokenService;
import ru.safeai.gateway.ratelimit.RateLimitRedisKeyProperties;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveValueToStringTest {

    private static final String PUBLIC_KEY =
            "-----BEGIN PUBLIC KEY-----PUBLIC-MATERIAL";
    private static final String PRIVATE_KEY =
            "-----BEGIN PRIVATE KEY-----PRIVATE-MATERIAL";
    private static final String HMAC_SECRET =
            "hmac-secret-with-at-least-thirty-two-bytes";
    private static final String PASSWORD =
            "NeverLogThisPassword!42";
    private static final String EMAIL =
            "private.person@example.test";
    private static final String FULL_NAME =
            "Private Person";
    private static final String REFRESH_TOKEN =
            "raw-refresh-token-never-log-this-value";

    @Test
    void sensitiveRecordsRedactSecretsAndPii() {
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        JwtProperties.KeyEntry key = new JwtProperties.KeyEntry(
                "active-key",
                PUBLIC_KEY,
                PRIVATE_KEY
        );
        JwtProperties jwt = new JwtProperties(
                15,
                "https://issuer.example.test",
                "safeai-api",
                "active-key",
                List.of(key)
        );
        RateLimitRedisKeyProperties redis =
                new RateLimitRedisKeyProperties(
                        "safeai:rate-limit",
                        HMAC_SECRET,
                        "v1"
                );
        LoginRequest login = new LoginRequest(EMAIL, PASSWORD);
        CreateUserRequest createUser = new CreateUserRequest(
                organizationId,
                EMAIL,
                PASSWORD,
                FULL_NAME,
                Set.of("ADMIN")
        );
        ResetUserPasswordRequest reset =
                new ResetUserPasswordRequest(PASSWORD, 1L);
        AccessTokenSubject subject = new AccessTokenSubject(
                userId,
                organizationId,
                0L,
                0L,
                Set.of("ADMIN")
        );
        CurrentUserResponse currentUser = new CurrentUserResponse(
                userId,
                organizationId,
                EMAIL,
                FULL_NAME,
                true,
                Set.of("ADMIN")
        );
        LoginSessionResult loginSession = new LoginSessionResult(
                currentUser,
                subject,
                REFRESH_TOKEN,
                Duration.ofDays(1)
        );
        RefreshTokenService.RefreshTokenRotationResult rotation =
                new RefreshTokenService.RefreshTokenRotationResult(
                        subject,
                        REFRESH_TOKEN,
                        Duration.ofDays(1)
                );
        RefreshTokenService.CreatedRefreshToken created =
                new RefreshTokenService.CreatedRefreshToken(
                        UUID.randomUUID(),
                        REFRESH_TOKEN,
                        Duration.ofDays(1)
                );

        assertRedacted(jwt, PUBLIC_KEY, PRIVATE_KEY);
        assertRedacted(key, PUBLIC_KEY, PRIVATE_KEY);
        assertRedacted(redis, HMAC_SECRET);
        assertRedacted(login, EMAIL, PASSWORD);
        assertRedacted(createUser, EMAIL, PASSWORD, FULL_NAME);
        assertRedacted(reset, PASSWORD);
        assertRedacted(loginSession, EMAIL, FULL_NAME, REFRESH_TOKEN);
        assertRedacted(rotation, REFRESH_TOKEN);
        assertRedacted(created, REFRESH_TOKEN);
    }

    private static void assertRedacted(
            Object value,
            String... forbiddenValues
    ) {
        assertThat(value.toString()).contains("<redacted");
        assertThat(value.toString()).doesNotContain(forbiddenValues);
    }
}
