package ru.safeai.gateway.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "issued_token_version", nullable = false)
    private long issuedTokenVersion;

    @Column(name = "issued_organization_auth_version", nullable = false)
    private long issuedOrganizationAuthVersion;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "family_created_at", nullable = false)
    private Instant familyCreatedAt;

    @Column(name = "family_expires_at", nullable = false)
    private Instant familyExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 40)
    private RefreshTokenRevocationReason revocationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_ip", length = 100)
    private String createdByIp;

    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    @Column(name = "token_family_id", nullable = false)
    private UUID tokenFamilyId;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            throw new IllegalStateException(
                    "RefreshTokenEntity.createdAt должен быть установлен service layer"
            );
        }
        if (issuedTokenVersion < 0
                || issuedOrganizationAuthVersion < 0) {
            throw new IllegalStateException(
                    "Issued security versions не могут быть отрицательными"
            );
        }
    }
}
