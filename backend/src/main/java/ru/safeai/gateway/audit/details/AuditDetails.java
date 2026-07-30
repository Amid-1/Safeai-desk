package ru.safeai.gateway.audit.details;

import java.util.Map;

@FunctionalInterface
public interface AuditDetails {
    Map<String, Object> toMap();
}
