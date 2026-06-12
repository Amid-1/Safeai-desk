package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class SafeAiJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public @NonNull AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        String userId = requiredClaim(jwt, "userId");
        String organizationId = requiredClaim(jwt, "organizationId");
        String email = jwt.getSubject();

        if (email == null || email.isBlank()) {
            throw new BadJwtException("JWT subject is missing");
        }

        String scope = jwt.getClaimAsString("scope");

        Set<SimpleGrantedAuthority> authorities = scope == null || scope.isBlank()
                ? Set.of()
                : Arrays.stream(scope.split(" "))
                .filter(role -> !role.isBlank())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());

        Boolean enabled = jwt.getClaimAsBoolean("enabled");

        SafeAiUserPrincipal principal = new SafeAiUserPrincipal(
                parseUuid(userId, "userId"),
                parseUuid(organizationId, "organizationId"),
                email,
                "",
                enabled == null || enabled,
                authorities
        );

        return new UsernamePasswordAuthenticationToken(
                principal,
                jwt,
                authorities
        );
    }

    private String requiredClaim(Jwt jwt, String claimName) {
        String value = jwt.getClaimAsString(claimName);

        if (value == null || value.isBlank()) {
            throw new BadJwtException("JWT claim is missing: " + claimName);
        }

        return value;
    }

    private UUID parseUuid(String value, String claimName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new BadJwtException("JWT claim is not valid UUID: " + claimName);
        }
    }
}