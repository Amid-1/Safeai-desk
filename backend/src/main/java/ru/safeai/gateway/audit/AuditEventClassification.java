package ru.safeai.gateway.audit;

import java.util.Arrays;
import java.util.List;

/** Классификация для расследования, не изменяет историческую запись аудита. */
public final class AuditEventClassification {

    public enum Category {
        SECURITY, USERS, ORGANIZATIONS, AI, KNOWLEDGE, SYSTEM
    }

    /** Критичность независима от результата операции. */
    public enum Metric {
        SUCCESS, DENIED, CRITICAL
    }

    private AuditEventClassification() {
    }

    public static Category category(AuditEventType type) {
        return switch (type) {
            case USER_LOGIN_SUCCESS, USER_LOGIN_FAILED, USER_LOGOUT,
                    RATE_LIMIT_EXCEEDED, SECURITY_REFRESH_REUSE_DETECTED -> Category.SECURITY;
            case USER_CREATED, USER_UPDATED, USER_ENABLED_CHANGED, USER_ROLES_CHANGED,
                    USER_PASSWORD_RESET, USER_PERMANENTLY_DELETED -> Category.USERS;
            case ORGANIZATION_CREATED, ORGANIZATION_NAME_CHANGED,
                    ORGANIZATION_ENABLED_CHANGED -> Category.ORGANIZATIONS;
            case CHAT_CREATED, CHAT_RENAMED, CHAT_ARCHIVED, CHAT_RESTORED,
                    CHAT_CONTENT_ERASURE_REQUESTED, CHAT_CONTENT_ERASURE_COMPLETED,
                    CHAT_CONTENT_ERASURE_REVIEW_REQUIRED, CHAT_CONTENT_ERASURE_RETENTION_BLOCKED,
                    CHAT_CONTENT_ERASURE_RESUMED, CHAT_CONTENT_ERASURE_CANCELLED,
                    CHAT_RETENTION_HOLD_RELEASED, CHAT_ERASURE_OPERATOR_APPROVED, CHAT_MESSAGE_SENT,
                    AI_RESPONSE_RECEIVED, AI_RESPONSE_FAILED, MODEL_CATALOG_VERSION_CREATED,
                    MODEL_POLICY_VERSION_CREATED, MODEL_ROUTE_DECIDED, MODEL_ROUTE_DENIED -> Category.AI;
            case KNOWLEDGE_BASE_CREATED, KNOWLEDGE_BASE_UPDATED, KNOWLEDGE_BASE_MEMBER_ADDED,
                    KNOWLEDGE_BASE_MEMBER_UPDATED, KNOWLEDGE_BASE_MEMBER_REMOVED,
                    KNOWLEDGE_BASE_DELETION_REQUESTED, KNOWLEDGE_BASE_DELETION_RESUMED,
                    KNOWLEDGE_BASE_DELETION_COMPLETED, KNOWLEDGE_BASE_DELETION_REVIEW_REQUIRED,
                    KNOWLEDGE_BASE_DELETION_RETENTION_BLOCKED, KNOWLEDGE_DOCUMENT_CREATED,
                    KNOWLEDGE_DOCUMENT_UPDATED, KNOWLEDGE_DOCUMENT_VERSION_UPLOADED,
                    KNOWLEDGE_DOCUMENT_DOWNLOADED, KNOWLEDGE_DOCUMENT_DELETION_REQUESTED,
                    KNOWLEDGE_DOCUMENT_DELETION_RESUMED, KNOWLEDGE_DOCUMENT_DELETION_COMPLETED,
                    KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED,
                    KNOWLEDGE_DOCUMENT_DELETION_RETENTION_BLOCKED, KNOWLEDGE_REINDEX_REQUESTED,
                    KNOWLEDGE_INGESTION_READY, KNOWLEDGE_INGESTION_FAILED,
                    KNOWLEDGE_RETRIEVAL_COMPLETED, KNOWLEDGE_EVALUATION_COMPLETED,
                    KNOWLEDGE_ANSWER_GENERATED -> Category.KNOWLEDGE;
            case AUDIT_OUTBOX_DEAD_LETTER_RECOVERED, RETENTION_POLICY_CHANGED -> Category.SYSTEM;
        };
    }

    public static boolean matches(AuditEventType type, Metric metric) {
        return switch (metric) {
            case SUCCESS -> switch (type) {
                case USER_LOGIN_SUCCESS, CHAT_CREATED, AI_RESPONSE_RECEIVED,
                        KNOWLEDGE_DOCUMENT_CREATED, KNOWLEDGE_DOCUMENT_VERSION_UPLOADED,
                        KNOWLEDGE_INGESTION_READY, MODEL_ROUTE_DECIDED, CHAT_CONTENT_ERASURE_COMPLETED,
                        KNOWLEDGE_BASE_DELETION_COMPLETED, KNOWLEDGE_DOCUMENT_DELETION_COMPLETED,
                        AUDIT_OUTBOX_DEAD_LETTER_RECOVERED -> true;
                default -> false;
            };
            case DENIED -> switch (type) {
                case USER_LOGIN_FAILED, MODEL_ROUTE_DENIED, RATE_LIMIT_EXCEEDED -> true;
                default -> false;
            };
            case CRITICAL -> type == AuditEventType.SECURITY_REFRESH_REUSE_DETECTED;
        };
    }

    public static List<String> types(Category category) {
        return Arrays.stream(AuditEventType.values()).filter(t -> category(t) == category)
                .map(Enum::name).toList();
    }

    public static List<String> types(Metric metric) {
        return Arrays.stream(AuditEventType.values()).filter(t -> matches(t, metric))
                .map(Enum::name).toList();
    }
}
