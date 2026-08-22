package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiErrorResponseWriter;

import java.io.IOException;

/**
 * Entry point только для OAuth2 Resource Server / Authorization: Bearer.
 */
@Component
@RequiredArgsConstructor
public final class BearerAuthenticationEntryPoint
implements AuthenticationEntryPoint {

    private final ApiErrorResponseWriter errorResponseWriter;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        errorResponseWriter.writeBearerUnauthorized(
                request,
                response,
                ApiErrorCode.UNAUTHORIZED,
                "Требуется авторизация"
        );
    }
}
