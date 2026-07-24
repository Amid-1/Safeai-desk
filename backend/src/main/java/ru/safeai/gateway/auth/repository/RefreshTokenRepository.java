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
     * Блокирует только одну строку refresh_tokens.
     * User/organization/roles загружаются отдельными запросами
     * внутри той же транзакции.
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
     * Терминально закрывает всю family.
     * Первоначальный revokedAt сохраняется, итоговая причина family
     * записывается во все её строки.
     */
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
                select u.id
                from UserEntity u
                where u.organization.id = :organizationId
            )
            """)
    int terminateAllByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") RefreshTokenRevocationReason reason
    );

    /**
     * Удаляет не более batchSize строк за одну короткую транзакцию.
     *
     * <p>SKIP LOCKED позволяет нескольким backend instances выполнять
     * cleanup одновременно: каждый экземпляр получает собственный набор
     * строк и не ждёт row locks другого экземпляра.</p>
     *
     * <p>Граница намеренно строгая: familyExpiresAt == threshold
     * ещё сохраняется.</p>
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    with candidates as (
                        select token.id
                        from refresh_tokens token
                        where token.family_expires_at < :threshold
                        order by token.family_expires_at, token.id
                        for update skip locked
                        limit :batchSize
                    )
                    delete from refresh_tokens token
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
