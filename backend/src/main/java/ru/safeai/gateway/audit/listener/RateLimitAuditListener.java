package ru.safeai.gateway.audit.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.ratelimit.RateLimitExceededEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitAuditListener {

    private final AuditEventService auditEventService;

    @EventListener
    public void onRateLimitExceeded(RateLimitExceededEvent event) {
        try {
            auditEventService.record(
                    event.userId(),
                    event.organizationId(),
                    AuditEventType.RATE_LIMIT_EXCEEDED,
                    event.details()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to write RATE_LIMIT_EXCEEDED audit event: userId={}, organizationId={}, type={}",
                    event.userId(),
                    event.organizationId(),
                    event.type(),
                    exception
            );
        }
    }
}