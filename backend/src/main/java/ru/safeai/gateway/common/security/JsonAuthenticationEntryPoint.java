package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull AuthenticationException authException
    ) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        JsonSecurityErrorWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Требуется авторизация. Передайте JWT токен в заголовке Authorization: Bearer <token>"
        );
    }
}