// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseRules.java
package ru.safeai.gateway.erasure;

import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Operation;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Target;

import java.util.Set;
import java.util.regex.Pattern;

/** Strictly typed R1a intake matrix. No executor access in this package. */
public final class ErasureCaseRules {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{12,128}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> REASONS = Set.of(
            "SELF_REQUEST", "OWNER_REQUEST", "ADMINISTRATIVE_REQUEST",
            "ORGANIZATION_REQUEST");
    private ErasureCaseRules() { }

    public static void validate(Target target) {
        if (target == null || target.resourceType() == null
                || target.resourceId() == null || target.operation() == null) {
            throw invalid();
        }
        boolean allowed = switch (target.resourceType()) {
            case CHAT -> target.operation() == Operation.REDACT_CONTENT
                    && "FULL_CHAT_CONTENT".equals(target.scope());
            case USER_ACCOUNT -> target.operation() == Operation.DELETE_ACCOUNT
                    && "PERSONAL_DATA_AND_ACCESS".equals(target.scope());
            case KNOWLEDGE_DOCUMENT -> target.operation() == Operation.DELETE_OBJECT
                    && "FULL_DOCUMENT".equals(target.scope());
            case KNOWLEDGE_BASE -> target.operation() == Operation.DELETE_OBJECT
                    && "FULL_BASE".equals(target.scope());
            case ORGANIZATION -> target.operation() == Operation.OFFBOARD_TENANT
                    && "TENANT_CLOSURE".equals(target.scope());
        };
        if (!allowed) throw invalid();
    }

    public static void key(String value) {
        if (value == null || !KEY.matcher(value).matches()) throw invalid();
    }

    public static void digest(String value) {
        if (value == null || !DIGEST.matcher(value).matches()) throw invalid();
    }

    public static void reason(String value) {
        if (value == null || !REASONS.contains(value)) throw invalid();
    }

    private static BadRequestException invalid() {
        return new BadRequestException("Некорректная область или параметры заявки.");
    }
}
