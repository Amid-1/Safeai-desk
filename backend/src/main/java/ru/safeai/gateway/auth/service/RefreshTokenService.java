package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;
import ru.safeai.gateway.common.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int MAX_USER_AGENT_LENGTH = 512;
    private static final int REFRESH_TOKEN_BYTES = 64;
    private static final int MAX_RAW_REFRESH_TOKEN_LENGTH = 256;
    private static final Pattern BASE64_URL_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final ClientIpResolver clientIpResolver;
    private final AuthCookieProperties authCookieProperties;
    private final Clock clock;

    public record RefreshTokenRotationResult(
            AccessTokenSubject accessTokenSubject,
            String rawRefreshToken
    ) {
    }

    private record CreatedRefreshToken(
            UUID id,
            String rawToken
    ) {
    }

    @Transactional
    public String create(UserEntity user, HttpServletRequest request) {
        Instant now = clock.instant();

        return createToken(
                user,
                request,
                UUID.randomUUID(),
                now
        ).rawToken();
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RefreshTokenRotationResult rotate(
            String rawToken,
            HttpServletRequest request
    ) {
        validateRawToken(rawToken);

        Instant now = clock.instant();

        RefreshTokenEntity oldToken = refreshTokenRepository
                .findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() ->
                        new InvalidRefreshTokenException(
                                "Refresh token не найден"
                        )
                );

        UserEntity user = oldToken.getUser();

        if (!user.isEnabled() || !user.getOrganization().isEnabled()) {
            refreshTokenRepository.revokeAllActiveByTokenFamilyId(
                    oldToken.getTokenFamilyId(),
                    now
            );

            throw new InvalidRefreshTokenException(
                    "Пользователь или организация отключены"
            );
        }

        if (oldToken.getRevokedAt() != null) {
            refreshTokenRepository.revokeAllActiveByTokenFamilyId(
                    oldToken.getTokenFamilyId(),
                    now
            );

            throw new RefreshTokenReuseDetectedException(
                    "Обнаружено повторное использование refresh token",
                    user.getId(),
                    user.getOrganization().getId(),
                    oldToken.getTokenFamilyId()
            );
        }

        if (!oldToken.getExpiresAt().isAfter(now)) {
            oldToken.setLastUsedAt(now);
            oldToken.setRevokedAt(now);

            throw new ExpiredRefreshTokenException(
                    "Refresh token истек"
            );
        }

        oldToken.setLastUsedAt(now);
        oldToken.setRevokedAt(now);

        CreatedRefreshToken newToken = createToken(
                user,
                request,
                oldToken.getTokenFamilyId(),
                now
        );

        oldToken.setReplacedByTokenId(newToken.id());

        return new RefreshTokenRotationResult(
                toAccessTokenSubject(user),
                newToken.rawToken()
        );
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }

        validateRawToken(rawToken);

        refreshTokenRepository.revokeByTokenHash(
                hash(rawToken),
                clock.instant()
        );
    }

    @Transactional
    public Optional<UserEntity> revokeAndReturnUser(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        validateRawToken(rawToken);

        Instant now = clock.instant();

        Optional<RefreshTokenEntity> token =
                refreshTokenRepository.findByTokenHashWithUser(
                        hash(rawToken)
                );

        token.ifPresent(refreshToken -> {
            if (refreshToken.getRevokedAt() == null) {
                refreshToken.setRevokedAt(now);
            }

            refreshToken.setLastUsedAt(now);
        });

        return token.map(RefreshTokenEntity::getUser);
    }

    private CreatedRefreshToken createToken(
            UserEntity user,
            HttpServletRequest request,
            UUID tokenFamilyId,
            Instant now
    ) {
        String rawToken = generateRawRefreshToken();
        UUID tokenId = UUID.randomUUID();

        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setId(tokenId);
        entity.setUser(user);
        entity.setTokenHash(hash(rawToken));
        entity.setTokenFamilyId(tokenFamilyId);
        entity.setExpiresAt(
                now.plus(authCookieProperties.refreshTokenMaxAge())
        );
        entity.setCreatedByIp(clientIpResolver.resolve(request));
        entity.setUserAgent(
                truncateUserAgent(request.getHeader("User-Agent"))
        );

        refreshTokenRepository.save(entity);

        return new CreatedRefreshToken(tokenId, rawToken);
    }

    private AccessTokenSubject toAccessTokenSubject(UserEntity user) {
        Set<String> roles = user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .collect(Collectors.toUnmodifiableSet());

        return new AccessTokenSubject(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getTokenVersion(),
                roles
        );
    }

    private void validateRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException(
                    "Refresh token не передан"
            );
        }

        if (rawToken.length() > MAX_RAW_REFRESH_TOKEN_LENGTH
                || !BASE64_URL_PATTERN.matcher(rawToken).matches()) {
            throw new InvalidRefreshTokenException(
                    "Некорректный формат refresh token"
            );
        }
    }

    private String generateRawRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] bytes = digest.digest(
                    rawToken.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(bytes);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }

    private String truncateUserAgent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.length() <= MAX_USER_AGENT_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
