package ru.safeai.gateway.auth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select distinct token
            from RefreshTokenEntity token
            join fetch token.user user
            join fetch user.organization
            left join fetch user.roles
            where token.tokenHash = :tokenHash
            """)
    Optional<RefreshTokenEntity> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    @Query("""
            select distinct token
            from RefreshTokenEntity token
            join fetch token.user user
            join fetch user.organization
            left join fetch user.roles
            where token.tokenHash = :tokenHash
            """)
    Optional<RefreshTokenEntity> findByTokenHashWithUser(
            @Param("tokenHash") String tokenHash
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
            set token.revokedAt = :revokedAt
            where token.tokenHash = :tokenHash
              and token.revokedAt is null
            """)
    void revokeByTokenHash(
            @Param("tokenHash") String tokenHash,
            @Param("revokedAt") Instant revokedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
            set token.revokedAt = :revokedAt
            where token.tokenFamilyId = :tokenFamilyId
              and token.revokedAt is null
            """)
    void revokeAllActiveByTokenFamilyId(
            @Param("tokenFamilyId") UUID tokenFamilyId,
            @Param("revokedAt") Instant revokedAt
    );

    /**
     * Вызывается из UserService в той же transaction,
     * где меняется passwordHash / enabled / roles / tokenVersion.

     * Важно не включать clearAutomatically=true, чтобы bulk update
     * не очистил persistence context текущей user-management операции.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
            set token.revokedAt = :revokedAt
            where token.user.id = :userId
              and token.revokedAt is null
            """)
    int revokeAllActiveByUserId(
            @Param("userId") UUID userId,
            @Param("revokedAt") Instant revokedAt
    );

    /**
     * Вызывается из OrganizationService в той же transaction,
     * где organization.enabled меняется на false.

     * При повторном включении организации refresh tokens не восстанавливаются.
     * Пользователи должны войти заново.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
            set token.revokedAt = :revokedAt
            where token.user.id in (
                select user.id
                from UserEntity user
                where user.organization.id = :organizationId
            )
              and token.revokedAt is null
            """)
    int revokeAllActiveByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("revokedAt") Instant revokedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from RefreshTokenEntity token
            where token.expiresAt < :threshold
               or token.revokedAt < :threshold
            """)
    int deleteExpiredAndRevokedBefore(
            @Param("threshold") Instant threshold
    );
}