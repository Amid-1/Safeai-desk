package ru.safeai.gateway.audit.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Audit-module SPI for capturing immutable target organization metadata.
 *
 * <p>The audit module depends on this abstraction instead of importing the
 * organization repository directly.</p>
 */
@FunctionalInterface
public interface AuditTargetOrganizationSnapshotProvider {

    Optional<String> findName(UUID organizationId);
}
