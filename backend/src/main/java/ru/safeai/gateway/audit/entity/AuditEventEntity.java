package ru.safeai.gateway.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(
            name = "actor_organization_id",
            updatable = false
    )
    private UUID actorOrganizationId;

    @Column(
            name = "actor_email",
            length = 255,
            updatable = false
    )
    private String actorEmail;

    @Column(
            name = "actor_display_name",
            length = 255,
            updatable = false
    )
    private String actorDisplayName;

    /**
     * Target organization события.
     */
    @Column(
            name = "organization_id",
            nullable = false,
            updatable = false
    )
    private UUID organizationId;

    /**
     * Immutable snapshot имени target organization на момент события.
     * Nullable только для legacy rows / аварийного best-effort snapshot.
     */
    @Column(
            name = "target_organization_name",
            length = 255,
            updatable = false
    )
    private String targetOrganizationName;

    @Column(
            name = "event_type",
            nullable = false,
            length = 100,
            updatable = false
    )
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "details",
            nullable = false,
            columnDefinition = "jsonb",
            updatable = false
    )
    private Map<String, Object> details = Map.of();

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @PrePersist
    void validateBeforePersist() {
        if (id == null) {
            throw new IllegalStateException(
                    "AuditEventEntity.id должен быть установлен явно"
            );
        }

        if (organizationId == null) {
            throw new IllegalStateException(
                    "AuditEventEntity.organizationId должен быть установлен явно"
            );
        }

        if (eventType == null || eventType.isBlank()) {
            throw new IllegalStateException(
                    "AuditEventEntity.eventType должен быть установлен явно"
            );
        }

        if (createdAt == null) {
            throw new IllegalStateException(
                    "AuditEventEntity.createdAt должен быть установлен явно"
            );
        }

        if (details == null) {
            details = Map.of();
        }
    }
}
