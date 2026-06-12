package ru.safeai.gateway.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;

    @Value("${app.security.jwt.expiration-minutes}")
    private long expirationMinutes;

    public String generateToken(SafeAiUserPrincipal user) {
        Objects.requireNonNull(user, "user не должен быть null");

        Instant now = Instant.now();

        String roles = user.getAuthorities()
                .stream()
                .filter(Objects::nonNull)
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("safeai-desk")
                .issuedAt(now)
                .expiresAt(now.plus(expirationMinutes, ChronoUnit.MINUTES))
                .subject(user.getEmail())
                .claim("userId", user.getId().toString())
                .claim("organizationId", user.getOrganizationId().toString())
                .claim("enabled", user.isEnabled())
                .claim("scope", roles)
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }
}