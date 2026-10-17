package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.spi.AuditActorSnapshotProvider;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mandatory and standalone durable audit API. */
@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditCommandFactory commandFactory;
    private final AuditOutboxWriter outboxWriter;
    private final AuditActorSnapshotProvider actorSnapshotProvider;

    /** Typed payload with fresh tenant-scoped actor snapshot, in caller transaction. */
    public void recordPrincipalSnapshot(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        record(resolveActorSnapshot(currentUser, null), targetOrganizationId, eventType, details);
    }

    @Deprecated(forRemoval = true)
    public void record(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(resolveActorSnapshot(currentUser, null), targetOrganizationId, eventType, details);
    }

    @Deprecated(forRemoval = true)
    public void record(
            SafeAiUserPrincipal currentUser,
            String actorDisplayName,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(resolveActorSnapshot(currentUser, actorDisplayName),
                targetOrganizationId, eventType, details);
    }

    @Deprecated(forRemoval = true)
    public void record(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        recordPrincipalSnapshot(currentUser, targetOrganizationId, eventType, details);
    }

    /**
     * Typed path MUST use createTyped. Forwarding to the raw Map overload would
     * incorrectly treat typed metadata as an untrusted legacy field map.
     */
    public void record(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        AuditCommand command = commandFactory.createTyped(
                actor, targetOrganizationId, eventType,
                Objects.requireNonNull(details, "details не должен быть null"));
        outboxWriter.writeRequiredInCurrentTransaction(command);
    }

    /** Legacy unstructured path retains the stricter allowlist sanitizer. */
    public void record(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        AuditCommand command = commandFactory.create(
                actor, targetOrganizationId, eventType, details);
        outboxWriter.writeRequiredInCurrentTransaction(command);
    }

    public void recordSystemRequiredInCurrentTransaction(
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(AuditActor.system(), targetOrganizationId, eventType, details);
    }

    /** An independent required outbox transaction for explicit standalone events. */
    public void recordStandaloneRequired(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        AuditCommand command = commandFactory.create(
                actor, targetOrganizationId, eventType, details);
        outboxWriter.writeStandaloneRequired(command);
    }

    public void recordSystemStandaloneRequired(
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        recordStandaloneRequired(AuditActor.system(), targetOrganizationId, eventType, details);
    }

    private AuditActor resolveActorSnapshot(
            SafeAiUserPrincipal currentUser,
            String explicitDisplayName
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        return actorSnapshotProvider.resolve(currentUser, explicitDisplayName);
    }
}
