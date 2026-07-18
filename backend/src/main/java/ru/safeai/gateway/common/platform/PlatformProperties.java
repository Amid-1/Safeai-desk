package ru.safeai.gateway.common.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "safeai.platform")
public record PlatformProperties(
        UUID organizationId
) {
    private static final UUID DEFAULT_PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public PlatformProperties {
        if (organizationId == null) {
            organizationId = DEFAULT_PLATFORM_ORGANIZATION_ID;
        }
    }
}
