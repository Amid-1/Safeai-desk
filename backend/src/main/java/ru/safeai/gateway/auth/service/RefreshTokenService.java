package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;
import ru.safeai.gateway.common.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.mapper.UserRoleMapper;
import ru.safeai.gateway.user.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int MAX_USER_AGENT_LENGTH = 512;
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final int REFRESH_TOKEN_BYTES = 64;
    private static final int MAX_RAW_REFRESH_TOKEN_LENGTH = 256;

    private static final Pattern BASE64_URL_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");

    private static final SecureRandom SECURE_RANDOM =
            new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final ClientIpResolver clientIpResolver;
    private final AuthCookieProperties authCookieProperties;
    private final Clock clock;

    public record RefreshTokenRotationResult(
            AccessTokenSubject accessTokenSubject,
            String rawRefreshToken,
            Duration refreshCookieMaxAge
    ) {
        public RefreshTokenRotationResult {
            Objects.requireNonNull(
                    accessTokenSubject,
                    "accessTokenSubject не должен быть null"
            );
            validateTokenResult(
                    rawRefreshToken,
                    "rawRefreshToken",
                    refreshCookieMaxAge,
                    "refreshCookieMaxAge"
            );
        }
    }

    public record CreatedRefreshToken(
            UUID id,
            String rawToken,
            Duration cookieMaxAge
    ) {
        public CreatedRefreshToken {
            Objects.requireNonNull(
                    id,
                    "id не должен быть null"
            );
            validateTokenResult(
                    rawToken,
                    "rawToken",
                    cookieMaxAge,
                    "cookieMaxAge"
            );
        }
    }

    /**
     * Создаёт первую refresh-token запись новой session family.
     * Вызывается только внутри login transaction после user lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CreatedRefreshToken createForLogin(
            UserEntity user,
            HttpServletRequest request,
            Instant now
    ) {
        Objects.requireNonNull(user, "user не должен быть null");
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(now, "now не должен быть null");

        Instant familyExpiresAt = now.plus(
                authCookieProperties.refreshTokenAbsoluteMaxAge()
        );

        return createToken(
                user,
                request,
                UUID.randomUUID(),
                now,
                now,
                familyExpiresAt
        );
    }

    /**
     * Строгая одноразовая rotation. Security termination updates должны
     * зафиксироваться даже при контролируемом refresh exception.
     */
    @Transactional(
            noRollbackFor = {
                    InvalidRefreshTokenException.class,
                    ExpiredRefreshTokenException.class,
                    RefreshTokenReuseDetectedException.class
            }
    )
    public RefreshTokenRotationResult rotate(
            String rawToken,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        validateRawToken(rawToken);

        Instant now = clock.instant();

        RefreshTokenEntity oldToken = refreshTokenRepository
                .findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException(
                        "Refresh token не найден"
                ));

        UUID userId = oldToken.getUser().getId();
        UUID familyId = oldToken.getTokenFamilyId();

        Optional<UserEntity> optionalUser = userRepository
                .findByIdWithRolesAndOrganization(userId);

        oldToken.setLastUsedAt(now);

        if (optionalUser.isEmpty()) {
            terminateFamily(
                    familyId,
                    now,
                    RefreshTokenRevocationReason.SECURITY_STATE_CHANGED
            );
            throw new InvalidRefreshTokenException(
                    "Пользователь refresh session не найден"
            );
        }

        UserEntity user = optionalUser.get();
        UUID organizationId = user.getOrganization().getId();

        if (oldToken.getRevokedAt() != null) {
            if (oldToken.getRevocationReason()
                    == RefreshTokenRevocationReason.ROTATED) {
                terminateFamily(
                        familyId,
                        now,
                        RefreshTokenRevocationReason.REUSE_DETECTED
                );
                throw new RefreshTokenReuseDetectedException(
                        "Обнаружено повторное использование refresh token",
                        userId,
                        organizationId,
                        familyId
                );
            }

            throw new InvalidRefreshTokenException(
                    "Refresh token отозван"
            );
        }

        if (!oldToken.getExpiresAt().isAfter(now)
                || !oldToken.getFamilyExpiresAt().isAfter(now)) {
            terminateFamily(
                    familyId,
                    now,
                    RefreshTokenRevocationReason.EXPIRED
            );
            throw new ExpiredRefreshTokenException(
                    "Refresh token истек"
            );
        }

        if (!user.isEnabled()) {
            terminateForSecurityState(
                    familyId,
                    now,
                    RefreshTokenRevocationReason.USER_DISABLED
            );
        }

        if (!user.getOrganization().isEnabled()) {
            terminateForSecurityState(
                    familyId,
                    now,
                    RefreshTokenRevocationReason.ORGANIZATION_DISABLED
            );
        }

        if (oldToken.getIssuedTokenVersion()
                != user.getTokenVersion()) {
            terminateForSecurityState(
                    familyId,
                    now,
                    RefreshTokenRevocationReason.SECURITY_STATE_CHANGED
            );
        }

        oldToken.setRevokedAt(now);
        oldToken.setRevocationReason(
                RefreshTokenRevocationReason.ROTATED
        );

        CreatedRefreshToken replacement = createToken(
                user,
                request,
                familyId,
                now,
                oldToken.getFamilyCreatedAt(),
                oldToken.getFamilyExpiresAt()
        );

        oldToken.setReplacedByTokenId(replacement.id());

        return new RefreshTokenRotationResult(
                toAccessTokenSubject(user),
                replacement.rawToken(),
                replacement.cookieMaxAge()
        );
    }

    /**
     * Logout блокирует предъявленную token row и закрывает всю family.
     */
    @Transactional
    public Optional<LogoutAuditSubject> revokeFamilyAndReturnSubject(
            String rawToken
    ) {
        validateRawToken(rawToken);

        Instant now = clock.instant();
        Optional<RefreshTokenEntity> optionalToken =
                refreshTokenRepository.findByTokenHashForUpdate(
                        hash(rawToken)
                );

        if (optionalToken.isEmpty()) {
            return Optional.empty();
        }

        RefreshTokenEntity token = optionalToken.get();

        if (token.getRevokedAt() != null
                && token.getRevocationReason()
                != RefreshTokenRevocationReason.ROTATED) {
            return Optional.empty();
        }

        UUID userId = token.getUser().getId();
        UUID familyId = token.getTokenFamilyId();

        Optional<UserEntity> optionalUser =
                userRepository.findByIdWithOrganization(userId);

        if (optionalUser.isEmpty()) {
            terminateFamily(
                    familyId,
                    now,
                    RefreshTokenRevocationReason.SECURITY_STATE_CHANGED
            );
            return Optional.empty();
        }

        UserEntity user = optionalUser.get();
        LogoutAuditSubject subject = new LogoutAuditSubject(
                user.getId(),
                user.getOrganization().getId(),
                canonicalEmail(user.getEmail())
        );

        terminateFamily(
                familyId,
                now,
                RefreshTokenRevocationReason.LOGOUT
        );

        return Optional.of(subject);
    }

    private void terminateForSecurityState(
            UUID familyId,
            Instant revokedAt,
            RefreshTokenRevocationReason reason
    ) {
        terminateFamily(familyId, revokedAt, reason);
        throw new InvalidRefreshTokenException(
                "Security state пользователя изменилось"
        );
    }

    private void terminateFamily(
            UUID familyId,
            Instant revokedAt,
            RefreshTokenRevocationReason reason
    ) {
        Objects.requireNonNull(familyId, "familyId не должен быть null");
        Objects.requireNonNull(revokedAt, "revokedAt не должен быть null");
        Objects.requireNonNull(reason, "reason не должен быть null");

        int affectedRows = refreshTokenRepository.terminateFamily(
                familyId,
                revokedAt,
                reason
        );

        if (affectedRows == 0) {
            log.warn(
                    "Refresh-token family termination affected no rows: "
                            + "familyId={}, reason={}",
                    familyId,
                    reason
            );
            return;
        }

        log.debug(
                "Refresh-token family terminated: familyId={}, "
                        + "affectedRows={}, reason={}",
                familyId,
                affectedRows,
                reason
        );
    }

    private CreatedRefreshToken createToken(
            UserEntity user,
            HttpServletRequest request,
            UUID tokenFamilyId,
            Instant now,
            Instant familyCreatedAt,
            Instant familyExpiresAt
    ) {
        Objects.requireNonNull(user, "user не должен быть null");
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(tokenFamilyId, "tokenFamilyId не должен быть null");
        Objects.requireNonNull(now, "now не должен быть null");
        Objects.requireNonNull(familyCreatedAt, "familyCreatedAt не должен быть null");
        Objects.requireNonNull(familyExpiresAt, "familyExpiresAt не должен быть null");

        if (!familyExpiresAt.isAfter(familyCreatedAt)) {
            throw new IllegalStateException(
                    "familyExpiresAt должен быть позже familyCreatedAt"
            );
        }

        Instant idleExpiresAt = now.plus(
                authCookieProperties.refreshTokenMaxAge()
        );
        Instant expiresAt = idleExpiresAt.isBefore(familyExpiresAt)
                ? idleExpiresAt
                : familyExpiresAt;

        if (!expiresAt.isAfter(now)) {
            throw new ExpiredRefreshTokenException(
                    "Абсолютный срок refresh session истек"
            );
        }

        String rawToken = generateRawRefreshToken();
        UUID tokenId = UUID.randomUUID();

        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setId(tokenId);
        entity.setUser(user);
        entity.setTokenHash(hash(rawToken));
        entity.setIssuedTokenVersion(user.getTokenVersion());
        entity.setTokenFamilyId(tokenFamilyId);
        entity.setCreatedAt(now);
        entity.setExpiresAt(expiresAt);
        entity.setFamilyCreatedAt(familyCreatedAt);
        entity.setFamilyExpiresAt(familyExpiresAt);
        entity.setCreatedByIp(clientIpResolver.resolve(request));
        entity.setUserAgent(
                truncateUserAgent(request.getHeader("User-Agent"))
        );

        refreshTokenRepository.save(entity);

        return new CreatedRefreshToken(
                tokenId,
                rawToken,
                cookieMaxAge(now, expiresAt)
        );
    }

    private AccessTokenSubject toAccessTokenSubject(UserEntity user) {
        return new AccessTokenSubject(
                user.getId(),
                user.getOrganization().getId(),
                canonicalEmail(user.getEmail()),
                user.getTokenVersion(),
                UserRoleMapper.toRoleNames(user)
        );
    }

    private void validateRawToken(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken не должен быть null");

        if (rawToken.isBlank()) {
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawToken.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }

    private Duration cookieMaxAge(Instant now, Instant expiresAt) {
        long seconds = Duration.between(now, expiresAt).toSeconds();
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    private String canonicalEmail(String email) {
        Objects.requireNonNull(email, "email не должен быть null");
        String canonical = email.trim().toLowerCase(Locale.ROOT);

        if (canonical.isBlank() || canonical.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalStateException(
                    "Некорректный email пользователя"
            );
        }

        return canonical;
    }

    private @Nullable String truncateUserAgent(
            @Nullable String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.length() <= MAX_USER_AGENT_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_USER_AGENT_LENGTH);
    }

    private static void validateTokenResult(
            String token,
            String tokenFieldName,
            Duration maxAge,
            String maxAgeFieldName
    ) {
        requireText(token, tokenFieldName);
        requirePositiveDuration(maxAge, maxAgeFieldName);
    }

    private static void requireText(String value, String fieldName) {
        Objects.requireNonNull(
                value,
                fieldName + " не должен быть null"
        );
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " не должен быть пустым"
            );
        }
    }

    private static void requirePositiveDuration(
            Duration value,
            String fieldName
    ) {
        Objects.requireNonNull(
                value,
                fieldName + " не должен быть null"
        );
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    fieldName + " должен быть положительным"
            );
        }
    }
}
