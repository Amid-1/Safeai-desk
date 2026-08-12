package ru.safeai.gateway.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import ru.safeai.gateway.organization.entity.OrganizationEntity;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@DynamicUpdate
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "organization_id",
            nullable = false
    )
    private OrganizationEntity organization;

    @Column(
            name = "email",
            nullable = false,
            length = 255
    )
    private String email;

    @Column(
            name = "password_hash",
            nullable = false,
            length = 255
    )
    private String passwordHash;

    @Column(
            name = "full_name",
            length = 255
    )
    private String fullName;

    @Column(
            name = "enabled",
            nullable = false
    )
    private boolean enabled;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns =
                    @JoinColumn(
                            name = "user_id"
                    ),
            inverseJoinColumns =
                    @JoinColumn(
                            name = "role_id"
                    )
    )
    private Set<RoleEntity> roles =
            new HashSet<>();

    @Generated(
            event = EventType.INSERT
    )
    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant createdAt;

    @Generated(
            event = {
                    EventType.INSERT,
                    EventType.UPDATE
            }
    )
    @Column(
            name = "updated_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(
            name = "token_version",
            nullable = false
    )
    private long tokenVersion;

    @Version
    @Column(
            name = "version",
            nullable = false
    )
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}