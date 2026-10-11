package ru.safeai.gateway.audit.spi;

import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

/**
 * Resolves an immutable actor snapshot from the canonical tenant-scoped user row.
 * The audit module owns this contract, not the user persistence implementation.
 */
@FunctionalInterface
public interface AuditActorSnapshotProvider {

    AuditActor resolve(SafeAiUserPrincipal principal, String explicitDisplayName);
}
