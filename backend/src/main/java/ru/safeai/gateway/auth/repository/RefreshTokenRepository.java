package ru.safeai.gateway.auth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.auth.entity.RefreshTokenEntity;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository
        extends JpaRepository<RefreshTokenEntity, UUID> {

    /**
     * Блокируется только строка refresh token.

     * User, organization и roles загружаются отдельным запросом
     * внутри той же транзакции RefreshTokenService.rotate().
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token
            from RefreshTokenEntity token
            where token.tokenHash = :tokenHash
            """)
    Optional<RefreshTokenEntity> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
            set token.revokedAt = coalesce(
                    token.revokedAt,
                    :revokedAt
                ),
                token.revocationReason = :reason
            where token.tokenFamilyId = :tokenFamilyId
            """)
    int terminateFamily(
            @Param("tokenFamilyId") UUID tokenFamilyId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") RefreshTokenRevocationReason reason
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
            set token.revokedAt = coalesce(
                    token.revokedAt,
                    :revokedAt
                ),
                token.revocationReason = :reason
            where token.user.id = :userId
            """)
    int terminateAllByUserId(
            @Param("userId") UUID userId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") RefreshTokenRevocationReason reason
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
            set token.revokedAt = coalesce(
                    token.revokedAt,
                    :revokedAt
                ),
                token.revocationReason = :reason
            where token.user.id in (
                select appUser.id
                from UserEntity appUser
                where appUser.organization.id = :organizationId
            )
            """)
    int terminateAllByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") RefreshTokenRevocationReason reason
    );

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    with candidates as (
                        select token.id
                        from public.refresh_tokens as token
                        where token.family_expires_at < :threshold
                        order by token.family_expires_at, token.id
                        for update of token skip locked
                        limit :batchSize
                    )
                    delete from public.refresh_tokens as token
                    using candidates
                    where token.id = candidates.id
                    """,
            nativeQuery = true
    )
    int deleteExpiredBatch(
            @Param("threshold") Instant threshold,
            @Param("batchSize") int batchSize
    );
}