package ru.safeai.gateway.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String TOKEN_TYPE = "JWT";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public String generateToken(
            AccessTokenSubject subject
    ) {
        Objects.requireNonNull(
                subject,
                "subject не должен быть null"
        );

        Instant issuedAt = clock.instant();

        Instant expiresAt = issuedAt.plus(
                Duration.ofMinutes(
                        jwtProperties.expirationMinutes()
                )
        );

        List<String> roles = subject.roles()
                .stream()
                .sorted()
                .toList();

        JwsHeader headers = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .type(TOKEN_TYPE)
                .keyId(jwtProperties.activeKeyId())
                .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(
                        List.of(jwtProperties.audience())
                )
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(subject.userId().toString())
                .id(UUID.randomUUID().toString())
                .claim(
                        "userId",
                        subject.userId().toString()
                )
                .claim(
                        "organizationId",
                        subject.organizationId().toString()
                )
                .claim(
                        "tokenVersion",
                        subject.tokenVersion()
                )
                .claim(
                        "organizationAuthVersion",
                        subject.organizationAuthVersion()
                )
                .claim("roles", roles)
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(
                        headers,
                        claims
                )
        ).getTokenValue();
    }
}
