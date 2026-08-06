package ru.safeai.gateway.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@DynamicUpdate
@Table(name = "organizations")
public class OrganizationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(
            name = "name",
            nullable = false,
            length = 255
    )
    private String name;

    @Column(
            name = "normalized_name",
            nullable = false,
            insertable = false,
            updatable = false,
            length = 255
    )
    private String normalizedName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        validateInvariant();
    }

    @PreUpdate
    void preUpdate() {
        validateInvariant();
    }

    private void validateInvariant() {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "OrganizationEntity.name не должен быть пустым"
            );
        }

        if (name.length() > 255) {
            throw new IllegalStateException(
                    "OrganizationEntity.name не должен превышать 255 символов"
            );
        }

        if (authVersion < 0L) {
            throw new IllegalStateException(
                    "OrganizationEntity.authVersion не может быть отрицательным"
            );
        }

        if (version < 0L) {
            throw new IllegalStateException(
                    "OrganizationEntity.version не может быть отрицательной"
            );
        }
    }
}
