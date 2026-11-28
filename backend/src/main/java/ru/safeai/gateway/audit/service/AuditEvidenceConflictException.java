package ru.safeai.gateway.audit.service;

import java.io.Serial;

/** Не раскрывает содержимое конфликтующих доказательств. */
final class AuditEvidenceConflictException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    AuditEvidenceConflictException() {
        super("Audit evidence mismatch for existing event ID");
    }
}
