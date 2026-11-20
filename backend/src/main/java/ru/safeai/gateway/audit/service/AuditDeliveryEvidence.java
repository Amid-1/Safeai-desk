package ru.safeai.gateway.audit.service;

import ru.safeai.gateway.audit.repository.AuditEventRepository;

import java.util.UUID;

final class AuditDeliveryEvidence {

    private AuditDeliveryEvidence() {
    }

    static void requireDelivered(AuditEventRepository repository, UUID id, int insertedRows) {
        if (insertedRows == 1) {
            return;
        }
        if (insertedRows != 0) {
            throw new IllegalStateException("Unexpected audit delivery result");
        }
        if (!repository.hasMatchingEvidence(id)) {
            throw new AuditEvidenceConflictException();
        }
    }
}
