package ru.safeai.gateway.identity.session;

/**
 * Cross-module reasons for invalidating authenticated sessions.
 * Auth-specific refresh-token lifecycle reasons remain inside the auth module.
 */
public enum SessionRevocationReason {
    PASSWORD_RESET,
    ROLE_CHANGED,
    EMAIL_CHANGED,
    USER_DISABLED,
    ORGANIZATION_DISABLED,
    SECURITY_STATE_CHANGED,
    ADMIN_REVOKED
}
