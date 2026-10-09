package ru.safeai.gateway.audit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Best-effort standalone audit facade.
 *
 * <p>События, записываемые через этот сервис, намеренно не должны влиять
 * на основной business flow: ошибка построения audit command, sanitization,
 * сохранения outbox row, flush или commit отдельной transaction логируется
 * и не пробрасывается вызывающему коду.</p>
 *
 * <p>Для security/business evidence, которое должно commit-иться атомарно
 * с основной mutation, этот сервис использовать нельзя. Для таких случаев
 * применяется required audit API текущей transaction.</p>
 */
@Slf4j
@Service
public final class BestEffortStandaloneAuditService {

    private final AuditCommandFactory commandFactory;
    private final AuditOutboxWriter outboxWriter;

    public BestEffortStandaloneAuditService(
            AuditCommandFactory commandFactory,
            AuditOutboxWriter outboxWriter
    ) {
        this.commandFactory =
                Objects.requireNonNull(
                        commandFactory,
                        "commandFactory не должен быть null"
                );

        this.outboxWriter =
                Objects.requireNonNull(
                        outboxWriter,
                        "outboxWriter не должен быть null"
                );
    }

    /**
     * Best-effort standalone audit для typed details.
     */
    public void tryRecord(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        tryWrite(
                actor,
                targetOrganizationId,
                eventType,
                () ->
                        commandFactory.createTyped(
                                actor,
                                targetOrganizationId,
                                eventType,
                                details
                        )
        );
    }

    /**
     * Best-effort standalone audit для legacy/unstructured details.
     */
    public void tryRecord(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        tryWrite(
                actor,
                targetOrganizationId,
                eventType,
                () ->
                        commandFactory.create(
                                actor,
                                targetOrganizationId,
                                eventType,
                                details
                        )
        );
    }

    /**
     * Best-effort standalone system audit.
     */
    public void tryRecordSystem(
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        tryRecord(
                AuditActor.system(),
                targetOrganizationId,
                eventType,
                details
        );
    }

    /**
     * Выполняет полный standalone audit pipeline:
     *
     * <ol>
     *     <li>создание и sanitization AuditCommand;</li>
     *     <li>REQUIRES_NEW durable outbox enqueue;</li>
     *     <li>перехват любой RuntimeException как best-effort failure.</li>
     * </ol>
     *
     * <p>Supplier намеренно вызывается внутри try-блока, чтобы ошибки
     * command factory и sanitizer имели ту же best-effort семантику,
     * что и ошибки persistence/transaction commit.</p>
     */
    private void tryWrite(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Supplier<AuditCommand> commandSupplier
    ) {
        Objects.requireNonNull(
                commandSupplier,
                "commandSupplier не должен быть null"
        );

        try {
            AuditCommand command =
                    commandSupplier.get();

            /*
             * writeStandaloneRequired использует REQUIRES_NEW.
             *
             * Вызов остаётся внутри try/catch, поэтому failure на любом
             * persistence этапе — save, flush или transaction completion —
             * сохраняет best-effort semantics этого facade.
             */
            outboxWriter.writeStandaloneRequired(
                    command
            );
        } catch (RuntimeException exception) {
            logFailure(
                    actor,
                    targetOrganizationId,
                    eventType,
                    exception
            );
        }
    }

    private void logFailure(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            RuntimeException exception
    ) {
        log.error(
                "Unable to persist standalone audit intent: "
                        + "eventType={}, actorUserId={}, "
                        + "actorOrganizationId={}, "
                        + "targetOrganizationId={}",
                eventType,
                actor == null
                        ? null
                        : actor.userId(),
                actor == null
                        ? null
                        : actor.organizationId(),
                targetOrganizationId,
                exception
        );
    }
}