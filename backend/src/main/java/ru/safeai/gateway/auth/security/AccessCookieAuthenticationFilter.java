package ru.safeai.gateway.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.safeai.gateway.auth.service.AuthCookieService;

import java.io.IOException;
import java.util.Objects;

/**
 * Аутентифицирует access JWT, полученный из HttpOnly cookie.
 *
 * <p>Фильтр намеренно выполняется после {@code CsrfFilter}.
 * Благодаря этому небезопасные cookie-authenticated запросы
 * сначала обязаны пройти CSRF-проверку.</p>
 *
 * <p>JWT из заголовка Authorization обрабатывается стандартным
 * {@code BearerTokenAuthenticationFilter}.</p>
 */
public final class AccessCookieAuthenticationFilter
        extends OncePerRequestFilter {

    private final AuthCookieService authCookieService;
    private final AuthenticationManager authenticationManager;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final RequestMatcher ignoredRequestMatcher;

    private final SecurityContextHolderStrategy
            securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    private final WebAuthenticationDetailsSource
            authenticationDetailsSource =
            new WebAuthenticationDetailsSource();

    public AccessCookieAuthenticationFilter(
            AuthCookieService authCookieService,
            AuthenticationManager authenticationManager,
            AuthenticationEntryPoint authenticationEntryPoint,
            RequestMatcher ignoredRequestMatcher
    ) {
        this.authCookieService = Objects.requireNonNull(
                authCookieService,
                "authCookieService не должен быть null"
        );

        this.authenticationManager = Objects.requireNonNull(
                authenticationManager,
                "authenticationManager не должен быть null"
        );

        this.authenticationEntryPoint = Objects.requireNonNull(
                authenticationEntryPoint,
                "authenticationEntryPoint не должен быть null"
        );

        this.ignoredRequestMatcher = Objects.requireNonNull(
                ignoredRequestMatcher,
                "ignoredRequestMatcher не должен быть null"
        );
    }

    @Override
    protected boolean shouldNotFilter(
            HttpServletRequest request
    ) {
        return ignoredRequestMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication existingAuthentication =
                securityContextHolderStrategy
                        .getContext()
                        .getAuthentication();

        /*
         * Authorization header имеет приоритет.
         * Если стандартный bearer-filter уже аутентифицировал
         * запрос, access-cookie повторно не обрабатывается.
         */
        if (existingAuthentication != null
                && existingAuthentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawAccessToken =
                authCookieService.extractAccessToken(request);

        if (rawAccessToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        BearerTokenAuthenticationToken authenticationRequest =
                new BearerTokenAuthenticationToken(
                        rawAccessToken
                );

        authenticationRequest.setDetails(
                authenticationDetailsSource.buildDetails(request)
        );

        final Authentication authenticationResult;

        try {
            authenticationResult =
                    authenticationManager.authenticate(
                            authenticationRequest
                    );
        } catch (AuthenticationException exception) {
            securityContextHolderStrategy.clearContext();

            authenticationEntryPoint.commence(
                    request,
                    response,
                    exception
            );
            return;
        }

        SecurityContext securityContext =
                securityContextHolderStrategy
                        .createEmptyContext();

        securityContext.setAuthentication(
                authenticationResult
        );

        securityContextHolderStrategy.setContext(
                securityContext
        );

        filterChain.doFilter(request, response);
    }
}