package ru.safeai.gateway.audit.listener;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.BestEffortStandaloneAuditService;
import ru.safeai.gateway.ratelimit.RateLimitExceededEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class RateLimitAuditListener {

    private final BestEffortStandaloneAuditService
            bestEffortAuditService;

    @EventListener
    public void onRateLimitExceeded(
            RateLimitExceededEvent event
    ) {
        Objects.requireNonNull(
                event,
                "event не должен быть null"
        );

        AuditActor actor = new AuditActor(
                event.actorUserId(),
                event.actorOrganizationId(),
                event.actorEmail(),
                event.actorDisplayName()
        );

        bestEffortAuditService.tryRecord(
                actor,
                event.targetOrganizationId(),
                AuditEventType.RATE_LIMIT_EXCEEDED,
                buildAuditDetails(event)
        );
    }

    private Map<String, Object> buildAuditDetails(
            RateLimitExceededEvent event
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>();

        if (event.details() != null) {
            result.putAll(event.details());
        }

        result.putIfAbsent(
                "type",
                event.type()
        );

        result.putIfAbsent(
                "limit",
                event.limit()
        );

        result.putIfAbsent(
                "window",
                event.window()
        );

        return Map.copyOf(result);
    }
}