package ru.safeai.gateway.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "safeai.cors")
public record CorsProperties(
        String allowedOrigins
) {
    public List<String> allowedOriginList() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return List.of();
        }

        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}