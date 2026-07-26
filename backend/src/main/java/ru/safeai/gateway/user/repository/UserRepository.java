package ru.safeai.gateway.user.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.user.entity.UserEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    /**
     * Параметр email должен быть каноническим: trim + lowercase.
     * V24 гарантирует такой же invariant и обычный unique(email) в PostgreSQL.
     */
    @EntityGraph(attributePaths = {
            "roles",
            "organization"
    })
    @Query("""
            select distinct user
            from UserEntity user
            where user.email = :email
            """)
    Optional<UserEntity> findByEmail(
            @Param("email") String email
    );

    @Query("""
            select count(user) > 0
            from UserEntity user
            where user.email = :email
            """)
    boolean existsByEmail(
            @Param("email") String email
    );

    @EntityGraph(attributePaths = {
            "roles",
            "organization"
    })
    @Query("""
            select distinct user
            from UserEntity user
            where user.id = :id
            """)
    Optional<UserEntity> findByIdWithRolesAndOrganization(
            @Param("id") UUID id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select user
            from UserEntity user
            where user.id = :id
            """)
    Optional<UserEntity> findByIdForSecurityUpdate(
            @Param("id") UUID id
    );

    @Query(
            value = """
                    select user.id
                    from UserEntity user
                    """,
            countQuery = """
                    select count(user)
                    from UserEntity user
                    """
    )
    Page<UUID> findAllIds(Pageable pageable);

    @Query(
            value = """
                    select user.id
                    from UserEntity user
                    where exists (
                        select 1
                        from UserEntity matchedUser
                        join matchedUser.roles role
                        where matchedUser = user
                          and role.name = :role
                    )
                    """,
            countQuery = """
                    select count(user.id)
                    from UserEntity user
                    where exists (
                        select 1
                        from UserEntity matchedUser
                        join matchedUser.roles role
                        where matchedUser = user
                          and role.name = :role
                    )
                    """
    )
    Page<UUID> findAllIdsByRole(
            @Param("role") String role,
            Pageable pageable
    );

    @Query(
            value = """
                    select user.id
                    from UserEntity user
                    where user.organization.id = :organizationId
                    """,
            countQuery = """
                    select count(user)
                    from UserEntity user
                    where user.organization.id = :organizationId
                    """
    )
    Page<UUID> findAllIdsByOrganizationId(
            @Param("organizationId") UUID organizationId,
            Pageable pageable
    );

    @Query(
            value = """
                    select user.id
                    from UserEntity user
                    where user.organization.id = :organizationId
                      and exists (
                          select 1
                          from UserEntity matchedUser
                          join matchedUser.roles role
                          where matchedUser = user
                            and role.name = :role
                      )
                    """,
            countQuery = """
                    select count(user.id)
                    from UserEntity user
                    where user.organization.id = :organizationId
                      and exists (
                          select 1
                          from UserEntity matchedUser
                          join matchedUser.roles role
                          where matchedUser = user
                            and role.name = :role
                      )
                    """
    )
    Page<UUID> findAllIdsByOrganizationIdAndRole(
            @Param("organizationId") UUID organizationId,
            @Param("role") String role,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {
            "roles",
            "organization"
    })
    @Query("""
            select distinct user
            from UserEntity user
            where user.id in :ids
            """)
    List<UserEntity> findAllByIdsWithRolesAndOrganization(
            @Param("ids") List<UUID> ids
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select user
            from UserEntity user
            where user.organization.id = :organizationId
              and user.enabled = true
              and exists (
                  select 1
                  from UserEntity matchedUser
                  join matchedUser.roles role
                  where matchedUser = user
                    and role.name = 'ADMIN'
              )
            order by user.id
            """)
    List<UserEntity> findEnabledAdminsForUpdate(
            @Param("organizationId") UUID organizationId
    );

    @EntityGraph(attributePaths = {
            "roles",
            "organization"
    })
    @Query("""
            select distinct user
            from UserEntity user
            where user.id = :id
              and user.organization.id = :organizationId
            """)
    Optional<UserEntity> findByIdAndOrganizationId(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId
    );

    @EntityGraph(attributePaths = "organization")
    @Query("""
            select user
            from UserEntity user
            where user.id = :id
            """)
    Optional<UserEntity> findByIdWithOrganization(
            @Param("id") UUID id
    );

    @Query("""
            select appUser.id
            from UserEntity appUser
            where appUser.organization.id = :organizationId
            order by appUser.id
            """)
    Slice<UUID> findIdsByOrganizationId(
            @Param("organizationId") UUID organizationId,
            Pageable pageable
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            
                update UserEntity user
            set user.tokenVersion = user.tokenVersion + 1
            where user.organization.id = :organizationId
            """)
    int incrementTokenVersionByOrganizationId(
            @Param("organizationId") UUID organizationId
    );

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    @Query(
            value = """
                    select exists (
                        select 1
                        from public.refresh_tokens as refresh_token
                        where refresh_token.user_id = :userId
                          and refresh_token.revoked_at is null
                          and refresh_token.expires_at > current_timestamp
                    )
                    """,
            nativeQuery = true
    )
    boolean hasActiveRefreshTokens(
            @Param("userId") UUID userId
    );

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    @Query(
            value = """
                    select exists (
                        select 1
                        from public.chat_sessions as chat_session
                        where chat_session.user_id = :userId
                    
                        union all
                    
                        select 1
                        from public.usage_daily_user_model_rollups
                                as usage_rollup
                        where usage_rollup.user_id = :userId
                    
                        union all
                    
                        select 1
                        from public.user_ai_quotas as user_quota
                        where user_quota.user_id = :userId
                    
                        union all
                    
                        select 1
                        from public.audit_events as audit_event
                        where audit_event.user_id = :userId
                           or audit_event.actor_user_id = :userId
                    
                        union all
                    
                        select 1
                        from public.audit_outbox as audit_outbox_event
                        where audit_outbox_event.actor_user_id = :userId
                    )
                    """,
            nativeQuery = true
    )
    boolean hasPermanentDeletionDependencies(
            @Param("userId") UUID userId
    );

    long countByOrganization_Id(UUID organizationId);

    long countByOrganization_IdAndEnabled(
            UUID organizationId,
            boolean enabled
    );

    long countByEnabled(boolean enabled);

    @Query("""
            select count(distinct user.id)
            from UserEntity user
            join user.roles role
            where role.name = :role
            """)
    long countByRole(
            @Param("role") String role
    );

    @Query("""
            select count(distinct user.id)
            from UserEntity user
            join user.roles role
            where user.organization.id = :organizationId
              and role.name = :role
            """)
    long countByOrganizationIdAndRole(
            @Param("organizationId") UUID organizationId,
            @Param("role") String role
    );
}
