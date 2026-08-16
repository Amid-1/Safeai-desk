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

    public void record(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(
                resolveActorSnapshot(
                        currentUser,
                        null
                ),
                targetOrganizationId,
                eventType,
                details
        );
    }

    public void record(
            SafeAiUserPrincipal currentUser,
            String actorDisplayName,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(
                resolveActorSnapshot(
                        currentUser,
                        actorDisplayName
                ),
                targetOrganizationId,
                eventType,
                details
        );
    }

    public void record(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        record(
                resolveActorSnapshot(
                        currentUser,
                        null
                ),
                targetOrganizationId,
                eventType,
                details
        );
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
                details == null
                        ? Map.of()
                        : details.toMap()
        );
    }

    public void record(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        AuditCommand command =
                commandFactory.create(
                        actor,
                        targetOrganizationId,
                        eventType,
                        details
                );

        /*
         * Присоединяется к transaction вызывающего business service.
         * Ошибка durable audit intent должна откатить security mutation.
         */
        outboxWriter.writeRequired(
                command
        );
    }

    public void recordSystem(
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

    /**
     * Access-token principal намеренно не содержит PII.
     *
     * <p>Поэтому email/fullName для immutable audit snapshot берутся
     * из текущей записи пользователя в БД в момент формирования
     * durable audit intent, а не из access JWT.</p>
     *
     * <p>Если пользователь уже отсутствует, сохраняем доступную
     * non-PII identity из principal. Это не мешает записать audit event
     * при редкой гонке удаления пользователя.</p>
     */
    private AuditActor resolveActorSnapshot(
            SafeAiUserPrincipal currentUser,
            String explicitDisplayName
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        UUID userId =
                currentUser.getId();

        UUID organizationId =
                currentUser.getOrganizationId();

        return userRepository
                .findByIdAndOrganizationId(
                        userId,
                        organizationId
                )
                .map(user ->
                        actorFromUser(
                                user,
                                userId,
                                organizationId,
                                explicitDisplayName
                        )
                )
                .orElseGet(() ->
                        AuditActor.fromPrincipal(
                                currentUser,
                                explicitDisplayName
                        )
                );
    }

    private AuditActor actorFromUser(
            UserEntity user,
            UUID userId,
            UUID organizationId,
            String explicitDisplayName
    ) {
        String displayName =
                explicitDisplayName == null
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