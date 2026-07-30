package ru.safeai.gateway.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "audit_outbox")
public class AuditOutboxEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_organization_id")
    private UUID actorOrganizationId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "actor_display_name", length = 255)
    private String actorDisplayName;

    /**
     * Существующий organization_id из V24 является target organization.
     */
    @Column(name = "organization_id", nullable = false)
    private UUID targetOrganizationId;

    @Column(
            name = "event_type",
            nullable = false,
            length = 100
    )
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "details",
            nullable = false,
            columnDefinition = "jsonb"
    )
    private Map<String, Object> details = Map.of();

    @Column(
            name = "occurred_at",
            nullable = false,
            updatable = false
    )
    private Instant occurredAt;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1_000)
    private String lastError;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @PrePersist
    @PreUpdate
    void validateState() {
        if (id == null) {
            throw new IllegalStateException(
                    "AuditOutboxEntity.id должен быть установлен service layer"
            );
        }

        if (occurredAt == null) {
            throw new IllegalStateException(
                    "AuditOutboxEntity.occurredAt должен быть установлен service layer"
            );
        }

        if (createdAt == null) {
            throw new IllegalStateException(
                    "AuditOutboxEntity.createdAt должен быть установлен service layer"
            );
        }

        if (attemptCount < 0) {
            throw new IllegalStateException(
                    "attemptCount не может быть отрицательным"
            );
        }

        if (deadLetteredAt == null
                && nextAttemptAt == null) {
            throw new IllegalStateException(
                    "Активная outbox row должна иметь nextAttemptAt"
            );
        }

        if (deadLetteredAt != null
                && nextAttemptAt != null) {
            throw new IllegalStateException(
                    "Dead-letter row не должна иметь nextAttemptAt"
            );
        }

        if (details == null) {
            details = Map.of();
        }
    }
}
