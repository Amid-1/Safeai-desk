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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public String generateToken(SafeAiUserPrincipal user) {
        Objects.requireNonNull(user, "user не должен быть null");

        Instant now = Instant.now();

        List<String> roles = RoleAuthorityMapper.toRoleNames(user.getAuthorities());

        if (roles.isEmpty()) {
            throw new IllegalStateException("Нельзя выпустить JWT для пользователя без ролей");
        }

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.expirationMinutes(), ChronoUnit.MINUTES))
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("userId", user.getId().toString())
                .claim("organizationId", user.getOrganizationId().toString())
                .claim("roles", roles)
                .claim("tokenVersion", user.getTokenVersion())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    public String generateAccessToken(UserEntity user) {
        Objects.requireNonNull(user, "user не должен быть null");
        Objects.requireNonNull(user.getId(), "user.id не должен быть null");
        Objects.requireNonNull(user.getOrganization(), "user.organization не должен быть null");
        Objects.requireNonNull(user.getOrganization().getId(), "user.organization.id не должен быть null");
        Objects.requireNonNull(user.getEmail(), "user.email не должен быть null");
        Objects.requireNonNull(user.getPasswordHash(), "user.passwordHash не должен быть null");
        Objects.requireNonNull(user.getRoles(), "user.roles не должен быть null");

        SafeAiUserPrincipal principal = new SafeAiUserPrincipal(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isEnabled(),
                user.getTokenVersion(),
                RoleAuthorityMapper.toAuthorities(
                        user.getRoles()
                                .stream()
                                .map(RoleEntity::getName)
                                .toList()
                )
        );

        return generateToken(principal);
    }
}