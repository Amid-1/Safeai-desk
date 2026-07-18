package ru.safeai.gateway.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public String generateToken(SafeAiUserPrincipal user) {
        Objects.requireNonNull(user, "user не должен быть null");

        return generateToken(new AccessTokenSubject(
                user.getId(),
                user.getOrganizationId(),
                user.getEmail(),
                user.getTokenVersion(),
                Set.copyOf(RoleAuthorityMapper.toRoleNames(user.getAuthorities()))
        ));
    }

    /**
     * Оставлен для совместимости с текущими call sites.
     * JWT service больше не использует password hash и не переносит JPA entity
     * в внутреннюю модель токена.
     */
    public String generateAccessToken(UserEntity user) {
        Objects.requireNonNull(user, "user не должен быть null");
        Objects.requireNonNull(user.getOrganization(), "user.organization не должен быть null");
        Objects.requireNonNull(user.getRoles(), "user.roles не должен быть null");

        Set<String> roles = user.getRoles().stream()
                .map(RoleEntity::getName)
                .collect(Collectors.toUnmodifiableSet());

        return generateToken(new AccessTokenSubject(
                Objects.requireNonNull(user.getId(), "user.id не должен быть null"),
                Objects.requireNonNull(
                        user.getOrganization().getId(),
                        "user.organization.id не должен быть null"
                ),
                Objects.requireNonNull(user.getEmail(), "user.email не должен быть null"),
                user.getTokenVersion(),
                roles
        ));
    }

    public String generateToken(AccessTokenSubject subject) {
        Objects.requireNonNull(subject, "subject не должен быть null");

        Instant now = clock.instant();
        Instant expiresAt = now.plus(
                Duration.ofMinutes(jwtProperties.expirationMinutes())
        );

        List<String> roles = RoleAuthorityMapper.toAuthorities(subject.roles())
                .stream()
                .map(authority -> authority.getAuthority().substring("ROLE_".length()))
                .sorted()
                .toList();

        if (roles.isEmpty()) {
            throw new IllegalStateException("Нельзя выпустить JWT для пользователя без ролей");
        }

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(subject.userId().toString())
                .id(UUID.randomUUID().toString())
                .claim("email", subject.email())
                .claim("userId", subject.userId().toString())
                .claim("organizationId", subject.organizationId().toString())
                .claim("roles", roles)
                .claim("tokenVersion", subject.tokenVersion())
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(headers, claims)
        ).getTokenValue();
    }
}
