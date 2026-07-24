package ru.safeai.gateway.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public final class CsrfCookieFilter
        extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Object attribute = request.getAttribute(
                CsrfToken.class.getName()
        );

        /*
         * Чтение token принудительно разрешает deferred CSRF token,
         * после чего CookieCsrfTokenRepository может сформировать cookie.
         */
        if (attribute instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }

        filterChain.doFilter(
                request,
                response
        );
    }
}