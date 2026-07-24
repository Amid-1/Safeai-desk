package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthAuditTransactionService {

    private final AuditEventService auditEventService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID userId,
            UUID organizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        auditEventService.record(
                userId,
                organizationId,
                eventType,
                details
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystem(
            UUID organizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        auditEventService.recordSystem(
                organizationId,
                eventType,
                details
        );
    }
}
