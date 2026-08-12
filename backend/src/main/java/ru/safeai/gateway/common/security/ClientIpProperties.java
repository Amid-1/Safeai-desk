package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NullUnmarked;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reverse-proxy trust boundary for ClientIpResolver.
 */
@NullUnmarked
@ConfigurationProperties(
        prefix = "safeai.security.client-ip"
)
public record ClientIpProperties(
        List<String> trustedProxyCidrs
) {
    public ClientIpProperties {
        if (trustedProxyCidrs == null
                || trustedProxyCidrs.isEmpty()) {
            trustedProxyCidrs = List.of();
        } else {
            Set<String> normalized =
                    new LinkedHashSet<>();

            for (String cidr
                    : trustedProxyCidrs) {
                if (cidr == null
                        || cidr.isBlank()) {
                    continue;
                }

                String value =
                        cidr.trim();

                validateCidr(value);
                normalized.add(value);
            }

            trustedProxyCidrs =
                    List.copyOf(normalized);
        }
    }

    private static void validateCidr(
            String cidr
    ) {
        try {
            new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Некорректный CIDR в "
                            + "safeai.security.client-ip."
                            + "trusted-proxy-cidrs: "
                            + cidr,
                    exception
            );
        }
    }
}
