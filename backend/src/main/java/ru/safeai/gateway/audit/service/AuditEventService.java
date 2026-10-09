package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditCommandFactory commandFactory;
    private final AuditOutboxWriter outboxWriter;
    private final UserRepository userRepository;

    /**
     * Records a typed audit event with a fresh, tenant-scoped actor snapshot.
     * The write joins the caller's business transaction and remains mandatory.
     */
    public void recordPrincipalSnapshot(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        record(
                resolveActorSnapshot(currentUser, null),
                targetOrganizationId,
                eventType,
                details
        );
    }

    /** Legacy entry point; retained for existing callers during migration. */
    @Deprecated(forRemoval = true)
    public void record(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(
                resolveActorSnapshot(currentUser, null),
                targetOrganizationId,
                eventType,
                details
        );
    }

    @Deprecated(forRemoval = true)
    public void record(
            SafeAiUserPrincipal currentUser,
            String actorDisplayName,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(
                resolveActorSnapshot(currentUser, actorDisplayName),
                targetOrganizationId,
                eventType,
                details
        );
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

    public void record(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        record(
                actor,
                targetOrganizationId,
                eventType,
                Objects.requireNonNull(details, "details не должен быть null").toMap()
        );
    }

    public void record(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        AuditCommand command = commandFactory.create(
                actor,
                targetOrganizationId,
                eventType,
                details
        );
        // Durable audit intent MUST share the business transaction.
        outboxWriter.writeRequiredInCurrentTransaction(command);
    }

    /**
     * Records a mandatory system audit event in the caller's transaction.
     *
     * <p>The outbox writer enforces {@code Propagation.MANDATORY} and flushes
     * the event before returning. A missing transaction or a persistence
     * failure is propagated to the caller; neither is silently ignored.</p>
     */
    public void recordSystemRequiredInCurrentTransaction(
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(
                AuditActor.system(),
                targetOrganizationId,
                eventType,
                details
        );
    }

    /** Isolated durable audit intent, deliberately outside the business transaction. */
    public void recordStandaloneRequired(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        AuditCommand command = commandFactory.create(
                actor,
                targetOrganizationId,
                eventType,
                details
        );
        outboxWriter.writeStandaloneRequired(command);
    }

    public void recordSystemStandaloneRequired(
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        recordStandaloneRequired(
                AuditActor.system(), targetOrganizationId, eventType, details
        );
    }

    /**
     * JWT principals have no PII. Resolve canonical actor identity from the
     * current tenant-scoped user row, falling back to non-PII principal data
     * only when that user row no longer exists.
     */
    private AuditActor resolveActorSnapshot(
            SafeAiUserPrincipal currentUser,
            String explicitDisplayName
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        UUID userId = currentUser.getId();
        UUID organizationId = currentUser.getOrganizationId();
        return userRepository.findByIdAndOrganizationId(userId, organizationId)
                .map(user -> actorFromUser(
                        user, userId, organizationId, explicitDisplayName
                ))
                .orElseGet(() -> AuditActor.fromPrincipal(
                        currentUser, explicitDisplayName
                ));
    }

    private AuditActor actorFromUser(
            UserEntity user,
            UUID userId,
            UUID organizationId,
            String explicitDisplayName
    ) {
        String displayName = explicitDisplayName == null
                || explicitDisplayName.isBlank()
                ? user.getFullName()
                : explicitDisplayName;
        return new AuditActor(
                userId,
                organizationId,
                user.getEmail(),
                displayName
        );
    }
}
