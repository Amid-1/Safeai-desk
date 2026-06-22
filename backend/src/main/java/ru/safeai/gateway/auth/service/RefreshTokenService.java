package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.user.entity.UserEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final ClientIpResolver clientIpResolver;

    @Transactional
    public String create(UserEntity user, HttpServletRequest request) {
        String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();

        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUser(user);
        entity.setTokenHash(hash(rawToken));
        entity.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        entity.setCreatedByIp(clientIpResolver.resolve(request));
        entity.setUserAgent(request.getHeader("User-Agent"));

        refreshTokenRepository.save(entity);

        return rawToken;
    }

    @Transactional(readOnly = true)
    public RefreshTokenEntity validate(String rawToken) {
        RefreshTokenEntity token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new IllegalArgumentException("Refresh token не найден"));

        if (!token.isActive()) {
            throw new IllegalArgumentException("Refresh token expired or revoked");
        }

        return token;
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                });
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash refresh token", exception);
        }
    }
}