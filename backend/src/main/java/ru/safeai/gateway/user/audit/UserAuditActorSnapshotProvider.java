package ru.safeai.gateway.user.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.spi.AuditActorSnapshotProvider;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserAuditActorSnapshotProvider implements AuditActorSnapshotProvider {

    private final UserRepository userRepository;

    /**
     * JWT principals do not carry user PII. Prefer the canonical tenant-scoped
     * user row, falling back to the principal only when the row no longer exists.
     * Repository failures must propagate and never silently degrade the audit.
     */
    @Override
    public AuditActor resolve(
            SafeAiUserPrincipal principal,
            String explicitDisplayName
    ) {
        Objects.requireNonNull(principal, "currentUser не должен быть null");
        UUID userId = principal.getId();
        UUID organizationId = principal.getOrganizationId();

        return userRepository.findByIdAndOrganizationId(userId, organizationId)
                .map(user -> actorFromUser(
                        user, userId, organizationId, explicitDisplayName
                ))
                .orElseGet(() -> AuditActor.fromPrincipal(
                        principal, explicitDisplayName
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
