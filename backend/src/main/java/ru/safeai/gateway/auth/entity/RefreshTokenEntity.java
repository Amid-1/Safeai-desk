package ru.safeai.gateway.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false
    )
    private UserEntity user;

    @Column(
            name = "token_hash",
            nullable = false,
            unique = true,
            length = 64,
            updatable = false
    )
    private String tokenHash;

    @Column(
            name = "issued_token_version",
            nullable = false,
            updatable = false
    )
    private long issuedTokenVersion;

    @Column(
            name = "token_family_id",
            nullable = false,
            updatable = false
    )
    private UUID tokenFamilyId;

    @Column(
            name = "family_created_at",
            nullable = false,
            updatable = false
    )
    private Instant familyCreatedAt;

    @Column(
            name = "family_expires_at",
            nullable = false,
            updatable = false
    )
    private Instant familyExpiresAt;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "expires_at",
            nullable = false,
            updatable = false
    )
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 40)
    private RefreshTokenRevocationReason revocationReason;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(
            name = "created_by_ip",
            length = 100,
            updatable = false
    )
    private String createdByIp;

    @Column(
            name = "user_agent",
            columnDefinition = "text",
            updatable = false
    )
    private String userAgent;
}
