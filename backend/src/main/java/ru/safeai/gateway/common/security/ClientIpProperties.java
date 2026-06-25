package ru.safeai.gateway.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;

@ConfigurationProperties(prefix = "safeai.security.client-ip")
public record ClientIpProperties(
        List<String> trustedProxyCidrs
) {
    public ClientIpProperties {
        trustedProxyCidrs = trustedProxyCidrs == null
                ? List.of()
                : trustedProxyCidrs.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}