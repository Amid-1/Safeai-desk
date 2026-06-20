package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ClientIpResolver {

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request не должен быть null");

        String forwardedFor = getHeaderValue(request, "X-Forwarded-For");

        if (forwardedFor != null) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = getHeaderValue(request, "X-Real-IP");

        if (realIp != null) {
            return realIp;
        }

        String remoteAddr = request.getRemoteAddr();

        return remoteAddr == null || remoteAddr.isBlank()
                ? "unknown"
                : remoteAddr.trim();
    }

    private String getHeaderValue(HttpServletRequest request, String name) {
        String value = request.getHeader(name);

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}