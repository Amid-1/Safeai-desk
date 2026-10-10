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
     * Unlocked scalar lookup solely to discover the lock order. Caller MUST
     * re-read the token after locking organization and user, and verify both
     * identities again. No security decision is based on this preflight.
     */
    @Query(value = """
            SELECT app_user.id, app_user.organization_id
              FROM public.refresh_tokens token
              JOIN public.users app_user ON app_user.id = token.user_id
             WHERE token.token_hash = :tokenHash
            """, nativeQuery = true)
    java.util.List<Object[]> findLockIdentityByTokenHash(
            @Param("tokenHash") String tokenHash);

    /**
     * Блокируется только строка refresh token.
     * <p>
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

    /**
     * Terminates still-active members of a family without destroying the
     * original provenance of tokens that were already revoked earlier.
     *
     * <p>Example: a predecessor that is already ROTATED remains ROTATED;
     * currently active descendants receive REUSE_DETECTED/LOGOUT/etc.</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
            set token.revokedAt = coalesce(
                    token.revokedAt,
                    :revokedAt
                ),
                token.revocationReason = coalesce(
                    token.revocationReason,
                    :reason
                )
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
                token.revocationReason = coalesce(
                    token.revocationReason,
                    :reason
                )
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
                token.revocationReason = coalesce(
                    token.revocationReason,
                    :reason
                )
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
                          and not exists (
                              select 1
                              from public.refresh_tokens as predecessor
                              where predecessor.replaced_by_token_id = token.id
                          )
                        order by
                            token.family_expires_at,
                            token.created_at,
                            token.id
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
