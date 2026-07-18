package ru.safeai.gateway.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.safeai.gateway.user.entity.UserEntity;

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

    /**
     * Текущая ссылка на пользователя.

     * Может стать null при удалении пользователя.
     * Не должна использоваться как исторический источник email/имени.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    /**
     * Неизменяемый UUID действующего пользователя на момент события.
     
     * Намеренно не имеет внешнего ключа.
     */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    /**
     * Неизменяемый email пользователя на момент события.
     */
    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    /**
     * Неизменяемое отображаемое имя пользователя на момент события.
     */
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
    private Map<String, Object> details = Map.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        if (createdAt == null) {
            createdAt = Instant.now();
        }

        if (details == null) {
            details = Map.of();
        }
    }
}