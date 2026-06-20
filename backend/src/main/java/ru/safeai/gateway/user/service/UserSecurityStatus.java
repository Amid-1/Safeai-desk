package ru.safeai.gateway.user.service;

public record UserSecurityStatus(
        boolean enabled,
        long tokenVersion
) {
}