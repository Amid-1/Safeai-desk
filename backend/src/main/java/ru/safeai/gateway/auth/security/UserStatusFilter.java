package ru.safeai.gateway.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.safeai.gateway.common.security.JsonSecurityErrorWriter;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.service.UserSecurityStatus;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatusFilter extends OncePerRequestFilter {

    private final UserStatusCacheService userStatusCacheService;
    private final JsonSecurityErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal()
                instanceof SafeAiUserPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Optional<UserSecurityStatus> optionalStatus =
                    userStatusCacheService.getStatus(
                            principal.getId()
                    );

            boolean valid = optionalStatus
                    .map(status -> isValid(status, principal))
                    .orElse(false);

            if (!valid) {
                rejectToken(request, response);
                return;
            }
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();

            log.error(
                    "Unable to validate user security state: userId={}",
                    principal.getId(),
                    exception
            );

            errorWriter.write(
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTH_STATUS_UNAVAILABLE",
                    "Сервис проверки авторизации временно недоступен"
            );

            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isValid(
            UserSecurityStatus status,
            SafeAiUserPrincipal principal
    ) {
        return status.userEnabled()
                && status.organizationEnabled()
                && status.organizationId().equals(
                        principal.getOrganizationId()
                )
                && status.tokenVersion()
                == principal.getTokenVersion()
                && status.organizationAuthVersion()
                == principal.getOrganizationAuthVersion();
    }

    private void rejectToken(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        SecurityContextHolder.clearContext();

        errorWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "TOKEN_REVOKED",
                "Токен больше не действителен"
        );
    }
}
