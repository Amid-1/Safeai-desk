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

        String userId =
                requiredStringClaim(
                        jwt,
                        "userId"
                );

        String organizationId =
                requiredStringClaim(
                        jwt,
                        "organizationId"
                );

        String email =
                requiredCanonicalEmail(jwt);

        long tokenVersion =
                requiredNonNegativeLongClaim(
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
                || roleValues.isEmpty()) {
            throw new BadJwtException(
                    "JWT claim is missing or invalid: roles"
            );
        }

        List<String> roles;

        try {
            roles = roleValues.stream()
                    .map(value -> {
                        if (!(value instanceof String role)) {
                            throw new IllegalArgumentException(
                                    "role is not a string"
                            );
                        }
                        return role;
                    })
                    .toList();
        } catch (RuntimeException exception) {
            throw new BadJwtException(
                    "JWT roles contain invalid values",
                    exception
            );
        }

        Set<SimpleGrantedAuthority> authorities;

        try {
            authorities =
                    RoleAuthorityMapper
                            .toAuthorities(roles);
        } catch (IllegalArgumentException
                 | NullPointerException exception) {
            throw new BadJwtException(
                    "JWT roles contain invalid values",
                    exception
            );
        }

        if (authorities.isEmpty()) {
            throw new BadJwtException(
                    "JWT roles are invalid"
            );
        }

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal
                        .accessTokenPrincipal(
                                parseUuid(
                                        userId,
                                        "userId"
                                ),
                                parseUuid(
                                        organizationId,
                                        "organizationId"
                                ),
                                email,
                                tokenVersion,
                                organizationAuthVersion,
                                authorities
                        );

        /*
         * Raw Jwt/token value намеренно не сохраняется
         * ни в principal, ни в credentials.
         */
        return UsernamePasswordAuthenticationToken
                .authenticated(
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

    private String requiredCanonicalEmail(
            Jwt jwt
    ) {
        String email =
                requiredStringClaim(
                        jwt,
                        "email"
                );

        try {
            return SecurityIdentityValidator
                    .requireCanonicalEmail(email);
        } catch (IllegalArgumentException
                 | NullPointerException exception) {
            throw new BadJwtException(
                    "JWT email is not canonical",
                    exception
            );
        }
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
                case Byte value ->
                        value.longValue();
                case Short value ->
                        value.longValue();
                case Integer value ->
                        value.longValue();
                case Long value ->
                        value;
                case BigInteger value ->
                        value.longValueExact();
                case BigDecimal value ->
                        value.longValueExact();
                case Double value -> {
                    if (!Double.isFinite(value)
                            || value % 1.0D != 0.0D) {
                        throw new ArithmeticException(
                                "not integral"
                        );
                    }

                    yield BigDecimal
                            .valueOf(value)
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
                            .valueOf(
                                    value.doubleValue()
                            )
                            .longValueExact();
                }
                default ->
                        throw new ArithmeticException(
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
