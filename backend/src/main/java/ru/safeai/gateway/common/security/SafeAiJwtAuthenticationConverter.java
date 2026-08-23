package ru.safeai.gateway.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class SafeAiJwtAuthenticationConverter
implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(
            Jwt jwt
    ) {
        Objects.requireNonNull(
                jwt,
                "jwt не должен быть null"
        );

        String subject = jwt.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new BadJwtException(
                    "JWT subject is missing"
            );
        }

        Instant issuedAt = jwt.getIssuedAt();

        if (issuedAt == null) {
            throw new BadJwtException(
                    "JWT issued-at claim is missing: iat"
            );
        }

        String jwtId = requiredStringClaim(
                jwt,
                "jti"
        );

        /*
         * jti является частью strict access-token schema и обязан быть UUID.
         * Значение сейчас не используется как server-side session id, но
         * валидируется, чтобы подписанный token не мог обходить schema contract.
         */
        parseUuid(jwtId, "jti");

        String userId = requiredStringClaim(
                jwt,
                "userId"
        );

        String organizationId = requiredStringClaim(
                jwt,
                "organizationId"
        );

        long tokenVersion = requiredNonNegativeLongClaim(
                jwt,
                "tokenVersion"
        );

        long organizationAuthVersion =
                requiredNonNegativeLongClaim(
                        jwt,
                        "organizationAuthVersion"
                );

        if (!subject.equals(userId)) {
            throw new BadJwtException(
                    "JWT subject does not match userId"
            );
        }

        Object rawRoles = jwt.getClaim("roles");

        if (!(rawRoles instanceof List<?> roleValues)
                || roleValues.size() != 1) {
            throw new BadJwtException(
                    "JWT must contain exactly one role"
            );
        }

        Object rawRole = roleValues.getFirst();

        if (!(rawRole instanceof String role)
                || role.isBlank()) {
            throw new BadJwtException(
                    "JWT roles contain invalid values"
            );
        }

        Set<SimpleGrantedAuthority> authorities;

        try {
            authorities = RoleAuthorityMapper.toAuthorities(
                    List.of(role)
            );
        } catch (IllegalArgumentException
                 | NullPointerException exception) {
            throw new BadJwtException(
                    "JWT roles contain invalid values",
                    exception
            );
        }

        if (authorities.size() != 1) {
            throw new BadJwtException(
                    "JWT must contain exactly one system role"
            );
        }

        SafeAiUserPrincipal principal;

        try {
            principal = SafeAiUserPrincipal.accessTokenPrincipal(
                    parseUuid(userId, "userId"),
                    parseUuid(
                            organizationId,
                            "organizationId"
                    ),
                    tokenVersion,
                    organizationAuthVersion,
                    authorities
            );
        } catch (IllegalArgumentException
                 | NullPointerException exception) {
            throw new BadJwtException(
                    "JWT identity claims are invalid",
                    exception
            );
        }

        /*
         * Raw Jwt/token value намеренно не сохраняется ни в principal,
         * ни в credentials.
         */
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                authorities
        );
    }

    private String requiredStringClaim(
            Jwt jwt,
            String claimName
    ) {
        Object raw = jwt.getClaim(claimName);

        if (!(raw instanceof String value)
                || value.isBlank()) {
            throw new BadJwtException(
                    "JWT claim is missing or invalid: "
                            + claimName
            );
        }

        return value;
    }

    private long requiredNonNegativeLongClaim(
            Jwt jwt,
            String claimName
    ) {
        Object raw = jwt.getClaim(claimName);

        if (!(raw instanceof Number number)) {
            throw new BadJwtException(
                    "JWT claim is not numeric: "
                            + claimName
            );
        }

        long result;

        try {
            result = switch (number) {
                case Byte value -> value.longValue();
                case Short value -> value.longValue();
                case Integer value -> value.longValue();
                case Long value -> value;
                case BigInteger value -> value.longValueExact();
                case BigDecimal value -> value.longValueExact();
                case Double value -> {
                    if (!Double.isFinite(value)
                            || value % 1.0D != 0.0D) {
                        throw new ArithmeticException(
                                "not integral"
                        );
                    }
                    yield BigDecimal.valueOf(value)
                            .longValueExact();
                }
                case Float value -> {
                    if (!Float.isFinite(value)
                            || value % 1.0F != 0.0F) {
                        throw new ArithmeticException(
                                "not integral"
                        );
                    }
                    yield BigDecimal
                            .valueOf(value.doubleValue())
                            .longValueExact();
                }
                default -> throw new ArithmeticException(
                        "unsupported numeric type"
                );
            };
        } catch (ArithmeticException exception) {
            throw new BadJwtException(
                    "JWT claim is not an integral long: "
                            + claimName,
                    exception
            );
        }

        if (result < 0L) {
            throw new BadJwtException(
                    "JWT claim is negative: "
                            + claimName
            );
        }

        return result;
    }

    private UUID parseUuid(
            String value,
            String claimName
    ) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new BadJwtException(
                    "JWT claim is not a valid UUID: "
                            + claimName,
                    exception
            );
        }
    }
}
