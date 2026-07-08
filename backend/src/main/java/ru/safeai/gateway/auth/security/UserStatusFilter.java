package ru.safeai.gateway.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.safeai.gateway.common.security.JsonSecurityErrorWriter;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class UserStatusFilter extends OncePerRequestFilter {

    private final UserStatusCacheService userStatusCacheService;
    private final JsonSecurityErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof SafeAiUserPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean valid = userStatusCacheService.getStatus(principal.getId())
                .map(status ->
                        status.userEnabled()
                                && status.organizationEnabled()
                                && status.tokenVersion() == principal.getTokenVersion()
                                && status.organizationId().equals(principal.getOrganizationId())
                )
                .orElse(false);

        if (!valid) {
            SecurityContextHolder.clearContext();

            errorWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "TOKEN_REVOKED",
                    "Токен больше не действителен"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}