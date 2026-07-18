package ru.safeai.gateway.user.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query("""
            select distinct u
            from UserEntity u
            where lower(u.email) = lower(:email)
            """)
    Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);

    @Query("""
            select count(u) > 0
            from UserEntity u
            where lower(u.email) = lower(:email)
            """)
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query("""
            select distinct u
            from UserEntity u
            where u.id = :id
            """)
    Optional<UserEntity> findByIdWithRolesAndOrganization(@Param("id") UUID id);

    @Query(
            value = "select u.id from UserEntity u",
            countQuery = "select count(u) from UserEntity u"
    )
    Page<UUID> findAllIds(Pageable pageable);

    @Query(
            value = """
                select u.id
                from UserEntity u
                where exists (
                    select 1
                    from UserEntity matchedUser
                    join matchedUser.roles role
                    where matchedUser = u
                      and role.name = :role
                )
                """,
            countQuery = """
                select count(u.id)
                from UserEntity u
                where exists (
                    select 1
                    from UserEntity matchedUser
                    join matchedUser.roles role
                    where matchedUser = u
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
                    select u.id
                    from UserEntity u
                    where u.organization.id = :organizationId
                    """,
            countQuery = """
                    select count(u)
                    from UserEntity u
                    where u.organization.id = :organizationId
                    """
    )
    Page<UUID> findAllIdsByOrganizationId(
            @Param("organizationId") UUID organizationId,
            Pageable pageable
    );

    @Query(
            value = """
                select u.id
                from UserEntity u
                where u.organization.id = :organizationId
                  and exists (
                      select 1
                      from UserEntity matchedUser
                      join matchedUser.roles role
                      where matchedUser = u
                        and role.name = :role
                  )
                """,
            countQuery = """
                select count(u.id)
                from UserEntity u
                where u.organization.id = :organizationId
                  and exists (
                      select 1
                      from UserEntity matchedUser
                      join matchedUser.roles role
                      where matchedUser = u
                        and role.name = :role
                  )
                """
    )
    Page<UUID> findAllIdsByOrganizationIdAndRole(
            @Param("organizationId") UUID organizationId,
            @Param("role") String role,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query("""
            select distinct u
            from UserEntity u
            where u.id in :ids
            """)
    List<UserEntity> findAllByIdsWithRolesAndOrganization(
            @Param("ids") List<UUID> ids
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select u
        from UserEntity u
        where u.organization.id = :organizationId
          and u.enabled = true
          and exists (
              select 1
              from UserEntity matchedUser
              join matchedUser.roles role
              where matchedUser = u
                and role.name = 'ADMIN'
          )
        order by u.id
        """)
    List<UserEntity> findEnabledAdminsForUpdate(
            @Param("organizationId") UUID organizationId
    );

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query("""
            select distinct u
            from UserEntity u
            where u.id = :id
              and u.organization.id = :organizationId
            """)
    Optional<UserEntity> findByIdAndOrganizationId(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId
    );

    @EntityGraph(attributePaths = {"organization"})
    @Query("""
            select u
            from UserEntity u
            where u.id = :id
            """)
    Optional<UserEntity> findByIdWithOrganization(@Param("id") UUID id);

    @Query("""
            select u.id
            from UserEntity u
            where u.organization.id = :organizationId
            """)
    List<UUID> findIdsByOrganizationId(
            @Param("organizationId") UUID organizationId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update UserEntity u
            set u.tokenVersion = u.tokenVersion + 1
            where u.organization.id = :organizationId
            """)
    int incrementTokenVersionByOrganizationId(
            @Param("organizationId") UUID organizationId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
        update UserEntity u
        set u.lastLoginAt = :lastLoginAt
        where u.id = :userId
        """)
    int updateLastLoginAt(
            @Param("userId") UUID userId,
            @Param("lastLoginAt") Instant lastLoginAt
    );

    @Query(
            value = """
                    select exists (
                        select 1
                        from chat_sessions
                        where user_id = :userId

                        union all

                        select 1
                        from usage_daily_user_model_rollups
                        where user_id = :userId
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
            select count(distinct u.id)
            from UserEntity u
            join u.roles r
            where r.name = :role
            """)
    long countByRole(@Param("role") String role);

    @Query("""
            select count(distinct u.id)
            from UserEntity u
            join u.roles r
            where u.organization.id = :organizationId
              and r.name = :role
            """)
    long countByOrganizationIdAndRole(
            @Param("organizationId") UUID organizationId,
            @Param("role") String role
    );
}
