package ru.safeai.gateway.common.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "safeai.platform")
public record PlatformProperties(
        UUID organizationId
) {
    public UUID effectiveOrganizationId() {
        return organizationId == null
                ? UUID.fromString("00000000-0000-0000-0000-000000000001")
                : organizationId;
    }
}