package ru.safeai.gateway.audit;

public final class AuditEventType {

    private AuditEventType() {
    }

    public static final String USER_LOGIN_SUCCESS = "USER_LOGIN_SUCCESS";
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";

    public static final String CHAT_CREATED = "CHAT_CREATED";
    public static final String CHAT_MESSAGE_SENT = "CHAT_MESSAGE_SENT";
    public static final String AI_RESPONSE_RECEIVED = "AI_RESPONSE_RECEIVED";
    public static final String AI_RESPONSE_FAILED = "AI_RESPONSE_FAILED";

    public static final String USER_CREATED = "USER_CREATED";
    public static final String ORGANIZATION_CREATED = "ORGANIZATION_CREATED";

    public static final String USER_ENABLED_CHANGED = "USER_ENABLED_CHANGED";
    public static final String USER_ROLES_CHANGED = "USER_ROLES_CHANGED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
}