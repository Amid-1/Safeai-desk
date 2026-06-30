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

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    delete from RefreshTokenEntity token
    where token.expiresAt < :threshold
       or token.revokedAt < :threshold
    """)
    void deleteExpiredAndRevokedBefore(
            @Param("threshold") Instant threshold
    );
}