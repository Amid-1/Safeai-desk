package ru.safeai.gateway.audit.listener;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.ratelimit.RateLimitExceededEvent;

@Component
@RequiredArgsConstructor
public class RateLimitAuditListener {

    private final AuditEventService auditEventService;

    @EventListener
    public void onRateLimitExceeded(RateLimitExceededEvent event) {
        auditEventService.record(
                event.userId(),
                event.organizationId(),
                AuditEventType.RATE_LIMIT_EXCEEDED,
                event.details()
        );
    }
}