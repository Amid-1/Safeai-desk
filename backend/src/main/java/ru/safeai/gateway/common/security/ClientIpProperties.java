package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NullUnmarked;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;
import java.util.Objects;

@NullUnmarked
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
                .map(ClientIpProperties::validatedCidr)
                .toList();
    }

    private static String validatedCidr(String cidr) {
        try {
            new IpAddressMatcher(cidr);
            return cidr;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Некорректный CIDR в "
                            + "safeai.security.client-ip.trusted-proxy-cidrs: "
                            + cidr,
                    exception
            );
        }
    }
}
