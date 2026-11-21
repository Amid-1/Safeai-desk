package ru.safeai.gateway.audit.repository;

import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AuditEventCursorRepository {

    Map<String, Long> countByEventType(UUID enforcedOrganizationId, AuditEventFilter filter);

    List<AuditEventEntity> findByCursor(
            UUID enforcedOrganizationId,
            AuditEventFilter filter,
            Instant beforeCreatedAt,
            UUID beforeId,
            int limit
    );
}
