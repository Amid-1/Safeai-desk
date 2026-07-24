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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class SafeAiJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final int MAX_EMAIL_LENGTH = 255;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new BadJwtException("JWT subject is missing");
        }

        String userId = requiredClaim(jwt, "userId");
        String organizationId = requiredClaim(jwt, "organizationId");
        String email = requiredCanonicalEmail(jwt);
        long tokenVersion = requiredNonNegativeTokenVersion(jwt);

        if (!subject.equals(userId)) {
            throw new BadJwtException(
                    "JWT subject does not match userId"
            );
        }

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
            throw new BadJwtException("JWT claim is missing: roles");
        }

        Set<SimpleGrantedAuthority> authorities;
        try {
            authorities = RoleAuthorityMapper.toAuthorities(roles);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BadJwtException(
                    "JWT roles contain invalid values",
                    exception
            );
        }

        if (authorities.isEmpty()) {
            throw new BadJwtException("JWT roles are invalid");
        }

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        parseUuid(userId, "userId"),
                        parseUuid(organizationId, "organizationId"),
                        email,
                        tokenVersion,
                        authorities
                );

        // Raw Jwt/token value намеренно не сохраняется в credentials.
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                authorities
        );
    }

    private long requiredNonNegativeTokenVersion(Jwt jwt) {
        Object raw = jwt.getClaim("tokenVersion");
        if (!(raw instanceof Number number)) {
            throw new BadJwtException(
                    "JWT claim is not numeric: tokenVersion"
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
                    if (!Double.isFinite(value) || value % 1 != 0) {
                        throw new ArithmeticException("not integral");
                    }
                    yield BigDecimal.valueOf(value).longValueExact();
                }
                case Float value -> {
                    if (!Float.isFinite(value) || value % 1 != 0) {
                        throw new ArithmeticException("not integral");
                    }
                    yield BigDecimal.valueOf(value.doubleValue())
                            .longValueExact();
                }
                default -> throw new ArithmeticException(
                        "unsupported numeric type"
                );
            };
        } catch (ArithmeticException exception) {
            throw new BadJwtException(
                    "JWT claim is not an integral long: tokenVersion"
            );
        }

        if (result < 0) {
            throw new BadJwtException(
                    "JWT claim is negative: tokenVersion"
            );
        }
        return result;
    }

    private String requiredCanonicalEmail(Jwt jwt) {
        String email = requiredClaim(jwt, "email");
        if (email.length() > MAX_EMAIL_LENGTH
                || !email.equals(email.trim())
                || !email.equals(email.toLowerCase(Locale.ROOT))) {
            throw new BadJwtException(
                    "JWT email claim is not canonical"
            );
        }
        return email;
    }

    private String requiredClaim(Jwt jwt, String claimName) {
        String value = jwt.getClaimAsString(claimName);
        if (value == null || value.isBlank()) {
            throw new BadJwtException(
                    "JWT claim is missing: " + claimName
            );
        }
        return value;
    }

    private UUID parseUuid(String value, String claimName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new BadJwtException(
                    "JWT claim is not valid UUID: " + claimName
            );
        }
    }
}
