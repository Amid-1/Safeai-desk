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
 * <p>Events recorded here deliberately do not change the outcome of a business
 * operation. Command creation, sanitization, outbox persistence and the separate
 * transaction's commit are covered by one best-effort failure boundary.</p>
 *
 * <p>Security/business evidence that must commit atomically with a mutation
 * must use the required audit API, never this facade.</p>
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
        this.commandFactory = Objects.requireNonNull(
                commandFactory, "commandFactory не должен быть null");
        this.outboxWriter = Objects.requireNonNull(
                outboxWriter, "outboxWriter не должен быть null");
    }

    /**
     * Typed audit detail entry point. Retained as an integration API even when
     * the current production call graph does not contain a direct caller.
     *
     * <p>Use only dedicated, reviewed AuditDetails implementations here:
     * the typed sanitizer allows structured metadata strings that the legacy
     * unstructured-map sanitizer intentionally rejects.</p>
     */
    @SuppressWarnings("unused") // Public module API; do not remove or redirect to create(Map).
    public void tryRecord(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        tryWrite(actor, targetOrganizationId, eventType,
                () -> commandFactory.createTyped(
                        actor, targetOrganizationId, eventType, details));
    }

    /** Legacy/unstructured details; keep its strict string-field allowlist. */
    public void tryRecord(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        tryWrite(actor, targetOrganizationId, eventType,
                () -> commandFactory.create(
                        actor, targetOrganizationId, eventType, details));
    }

    /** Best-effort standalone system audit using the unstructured boundary. */
    public void tryRecordSystem(
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        tryRecord(AuditActor.system(), targetOrganizationId, eventType, details);
    }

    /**
     * Supplier runs inside the catch boundary: failures before persistence,
     * during flush and at commit all retain the same best-effort semantics.
     * The writer remains a separate Spring bean so REQUIRES_NEW is effective.
     */
    private void tryWrite(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Supplier<AuditCommand> commandSupplier
    ) {
        Objects.requireNonNull(
                commandSupplier, "commandSupplier не должен быть null");
        try {
            AuditCommand command = commandSupplier.get();
            outboxWriter.writeStandaloneRequired(command);
        } catch (RuntimeException exception) {
            logFailure(actor, targetOrganizationId, eventType, exception);
        }
    }

    private void logFailure(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            RuntimeException exception
    ) {
        /*
         * This is a security-sensitive log boundary. Throwable messages and
         * stack traces can contain SQL bind values, credentials, prompts or
         * provider response bodies. Passing the Throwable to SLF4J as the
         * final argument would print them. Only the stable exception TYPE
         * is logged. Never log exception.getMessage() or exception.toString().
         */
        log.error(
                "Unable to persist standalone audit intent: "
                        + "eventType={}, actorUserId={}, "
                        + "actorOrganizationId={}, targetOrganizationId={}, "
                        + "failureType={}",
                eventType,
                actor == null ? null : actor.userId(),
                actor == null ? null : actor.organizationId(),
                targetOrganizationId,
                exception.getClass().getName()
        );
    }
}
