package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
        String userId = jwt.getClaimAsString("userId");
        String organizationId = jwt.getClaimAsString("organizationId");
        String email = jwt.getSubject();
        String scope = jwt.getClaimAsString("scope");

        Set<SimpleGrantedAuthority> authorities = scope == null || scope.isBlank()
                ? Set.of()
                : Arrays.stream(scope.split(" "))
                .filter(role -> !role.isBlank())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());

        SafeAiUserPrincipal principal = new SafeAiUserPrincipal(
                UUID.fromString(userId),
                UUID.fromString(organizationId),
                email,
                "",
                true,
                authorities
        );

        return new UsernamePasswordAuthenticationToken(
                principal,
                jwt,
                authorities
        );
    }
}