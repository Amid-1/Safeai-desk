package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class SafeAiJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public @NonNull AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        String subject = jwt.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new BadJwtException("JWT subject is missing");
        }

        String userId = requiredClaim(jwt, "userId");
        String organizationId = requiredClaim(jwt, "organizationId");
        String email = requiredClaim(jwt, "email");

        if (!subject.equals(userId)) {
            throw new BadJwtException("JWT subject does not match userId");
        }

        Number tokenVersionClaim = jwt.getClaim("tokenVersion");

        if (tokenVersionClaim == null) {
            throw new BadJwtException("JWT claim is missing: tokenVersion");
        }

        long tokenVersion = tokenVersionClaim.longValue();

        if (tokenVersion < 0) {
            throw new BadJwtException("JWT tokenVersion is invalid");
        }

        List<String> roles = jwt.getClaimAsStringList("roles");

        if (roles == null || roles.isEmpty()) {
            throw new BadJwtException("JWT claim is missing: roles");
        }

        Set<SimpleGrantedAuthority> authorities;

        try {
            authorities = RoleAuthorityMapper.toAuthorities(roles);
        } catch (IllegalArgumentException exception) {
            throw new BadJwtException("JWT roles contain unknown values");
        }

        if (authorities.isEmpty()) {
            throw new BadJwtException("JWT roles are invalid");
        }

        /*
         * JWT не является источником актуального enabled/status.
         * Реальный статус пользователя и организации проверяется UserStatusFilter.
         */
        SafeAiUserPrincipal principal = new SafeAiUserPrincipal(
                parseUuid(userId, "userId"),
                parseUuid(organizationId, "organizationId"),
                email,
                "",
                true,
                tokenVersion,
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