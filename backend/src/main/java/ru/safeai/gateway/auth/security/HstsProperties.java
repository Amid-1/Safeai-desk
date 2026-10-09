package ru.safeai.gateway.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HSTS policy for the public application security boundary.
 *
 * <p>{@code enabled=true} means Spring emits HSTS itself.
 * {@code edgeManaged=true} means the deployment contract requires the trusted
 * TLS terminator / ingress / load balancer to emit HSTS instead.</p>
 *
 * <p>Production startup validation requires at least one of these guarantees.
 * Local/test HTTP can keep both values false.</p>
 */
@ConfigurationProperties(prefix = "safeai.security.hsts")
public record HstsProperties(
        boolean enabled,
        boolean edgeManaged
) {
}
