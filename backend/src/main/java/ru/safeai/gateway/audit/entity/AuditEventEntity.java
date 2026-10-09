package ru.safeai.gateway.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Materialized immutable audit evidence.
 *
 * <p>V68 also rejects UPDATE at the PostgreSQL boundary. {@link Immutable}
 * prevents normal Hibernate dirty checking from pretending this is a mutable
 * aggregate.</p>
 */
@Getter
@Immutable
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            updatable = false
    )
    private UUID id;

    @Column(
            name = "actor_user_id",
            updatable = false
    )
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

    @Column(
            name = "organization_id",
            nullable = false,
            updatable = false
    )
    private UUID organizationId;

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
}
