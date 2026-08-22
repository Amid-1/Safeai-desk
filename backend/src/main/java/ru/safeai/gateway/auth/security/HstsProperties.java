package ru.safeai.gateway.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HSTS policy for the public application security filter chain.
 *
 * <p>Disabled by default so local HTTP/test environments are not forced
 * into HTTPS. Production deployment should explicitly enable it only when
 * HTTPS is guaranteed at the public edge.</p>
 */
@ConfigurationProperties(prefix = "safeai.security.hsts")
public record HstsProperties(
        boolean enabled
) {
}
