package ru.safeai.gateway.user.service;

public record UserSecurityStatus(
        boolean userEnabled,
        boolean organizationEnabled,
        long tokenVersion
) {
}