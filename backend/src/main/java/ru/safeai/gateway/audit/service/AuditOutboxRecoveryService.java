package ru.safeai.gateway.audit.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDeadLetterRecoveryDetails;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;

import java.util.Objects;
import java.util.UUID;

/**
 * Operator-controlled recovery from a terminal V68 dead-letter row.
 *
 * <p>V68 forbids resetting dead_lettered_at/attempt_count. Accordingly,
 * this service invokes the same idempotent INSERT ... ON CONFLICT DO NOTHING
 * and evidence comparison as the normal processor, removes the original row after successful delivery,
 * and enqueues a separate required audit event in ONE database transaction.
 * Any exception rolls back all three steps, retaining the original dead-letter.
 */
@Service
public class AuditOutboxRecoveryService {

    private final AuditOutboxRepository outboxRepository;
    private final AuditEventRepository eventRepository;
    private final AuditEventService auditEventService;
    private final AuditOutboxMetrics metrics;

    public AuditOutboxRecoveryService(
            AuditOutboxRepository outboxRepository,
            AuditEventRepository eventRepository,
            AuditEventService auditEventService,
            AuditOutboxMetrics metrics
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
        this.auditEventService = Objects.requireNonNull(auditEventService, "auditEventService");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recover(UUID deadLetterId, SafeAiUserPrincipal operator) {
        Objects.requireNonNull(deadLetterId, "deadLetterId");
        if (operator == null || !operator.authorityNames()
                .contains(SystemRole.SUPER_ADMIN.authority())) {
            throw new ForbiddenOperationException("Восстановление audit dead-letter доступно только SUPER_ADMIN");
        }

        AuditOutboxEntity row = outboxRepository.findByIdForUpdate(deadLetterId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Audit dead-letter event не найден"));
        if (row.getDeadLetteredAt() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Audit outbox event ещё находится в обычной очереди");
        }

        int inserted = eventRepository.insertFromOutbox(deadLetterId);
        try {
            AuditDeliveryEvidence.requireDelivered(eventRepository, deadLetterId, inserted);
        } catch (AuditEvidenceConflictException failure) {
            metrics.recordIntegrityConflict();
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Обнаружено несовпадение доказательств аудита; запись сохранена");
        }

        auditEventService.recordPrincipalSnapshot(
                operator,
                row.getTargetOrganizationId(),
                AuditEventType.AUDIT_OUTBOX_DEAD_LETTER_RECOVERED,
                new AuditDeadLetterRecoveryDetails(deadLetterId, row.getAttemptCount())
        );

        outboxRepository.delete(row);
        outboxRepository.flush();
    }
}
