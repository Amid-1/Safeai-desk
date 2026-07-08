package ru.safeai.gateway.user.service;

import java.util.UUID;

public record UserSecurityStatus(
        UUID organizationId,
        boolean userEnabled,
        boolean organizationEnabled,
        long tokenVersion
) {
}