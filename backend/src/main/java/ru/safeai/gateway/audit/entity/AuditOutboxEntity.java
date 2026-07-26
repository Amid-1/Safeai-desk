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
@Table(name = "audit_outbox")
public class AuditOutboxEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "actor_display_name", length = 255)
    private String actorDisplayName;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "details",
            nullable = false,
            columnDefinition = "jsonb"
    )
    private Map<String, Object> details;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        if (createdAt == null) {
            throw new IllegalStateException(
                    "AuditOutboxEntity.createdAt должен быть установлен service layer"
            );
        }

        if (details == null) {
            details = Map.of();
        }
    }
}
