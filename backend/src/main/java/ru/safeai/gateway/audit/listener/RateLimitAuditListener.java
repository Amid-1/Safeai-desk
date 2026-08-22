package ru.safeai.gateway.audit.listener;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.ratelimit.RateLimitExceededEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class RateLimitAuditListener {

    private final AuditEventService auditEventService;

    /**
     * Listener намеренно синхронный.
     *
     * <p>Durable enqueue выполняется через отдельную REQUIRES_NEW
     * transaction. Поэтому последующий rollback business transaction
     * не удаляет RATE_LIMIT_EXCEEDED audit intent.</p>
     *
     * <p>Ошибка durable enqueue должна выйти обратно в publisher.
     * Тогда rate-limit service сможет освободить notification marker
     * и повторить enqueue на следующем rejected request.</p>
     */
    @EventListener
    public void onRateLimitExceeded(
            RateLimitExceededEvent event
    ) {
        Objects.requireNonNull(
                event,
                "event не должен быть null"
        );

        Map<String, Object> details =
                buildAuditDetails(
                        event
                );

        /*
         * Login rejection возникает до аутентификации,
         * поэтому actorUserId отсутствует.
         *
         * Не пытаемся искать пользователя по введённому email:
         * пишем системное событие в targetOrganizationId.
         */
        if (event.actorUserId() == null) {
            auditEventService.recordSystemStandaloneRequired(
                    event.targetOrganizationId(),
                    AuditEventType.RATE_LIMIT_EXCEEDED,
                    details
            );

            return;
        }

        /*
         * actorEmail / actorDisplayName могут быть null.
         *
         * Это штатно для access-token principal:
         * access JWT намеренно не содержит PII.
         * AuditActor поддерживает частичный snapshot identity.
         */
        AuditActor actor =
                new AuditActor(
                        event.actorUserId(),
                        event.actorOrganizationId(),
                        event.actorEmail(),
                        event.actorDisplayName()
                );

        auditEventService.recordStandaloneRequired(
                actor,
                event.targetOrganizationId(),
                AuditEventType.RATE_LIMIT_EXCEEDED,
                details
        );
    }

    private Map<String, Object> buildAuditDetails(
            RateLimitExceededEvent event
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>(
                        event.details()
                );

        result.putIfAbsent(
                "type",
                event.type()
        );

        /*
         * Для BOTH limit намеренно null:
         * у события два независимых лимита.
         *
         * Map.copyOf не допускает null values,
         * поэтому поле добавляется только
         * для одномерного превышения.
         */
        if (event.limit() != null) {
            result.putIfAbsent(
                    "limit",
                    event.limit()
            );
        }

        result.putIfAbsent(
                "window",
                event.window()
        );

        return Map.copyOf(
                result
        );
    }
}
