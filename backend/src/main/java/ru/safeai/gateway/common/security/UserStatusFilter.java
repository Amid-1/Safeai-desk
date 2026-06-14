package ru.safeai.gateway.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.safeai.gateway.user.repository.UserRepository;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class UserStatusFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final JsonSecurityErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof SafeAiUserPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean valid = userRepository.findById(principal.getId())
                .map(user -> user.isEnabled() && user.getTokenVersion() == principal.getTokenVersion())
                .orElse(false);

        if (!valid) {
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