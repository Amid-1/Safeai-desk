package ru.safeai.gateway.audit.dto;

/** Серверные счётчики по полной ограниченной выборке, до пагинации. */
public record AuditEventSummary(long total, long successful, long denied, long critical) {
}
