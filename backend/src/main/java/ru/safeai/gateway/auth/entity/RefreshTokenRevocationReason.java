package ru.safeai.gateway.auth.entity;

public enum RefreshTokenRevocationReason {
    ROTATED,
    LOGOUT,
    PASSWORD_RESET,
    ROLE_CHANGED,
    EMAIL_CHANGED,
    USER_DISABLED,
    ORGANIZATION_DISABLED,
    SECURITY_STATE_CHANGED,
    EXPIRED,
    REUSE_DETECTED,
    ADMIN_REVOKED,
    LEGACY_REVOKED
}
