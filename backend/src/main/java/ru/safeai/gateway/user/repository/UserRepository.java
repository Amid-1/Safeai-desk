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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository
        extends JpaRepository<UserEntity, UUID> {

    /**
     * Загружает пользователя для password authentication.

     * Email предварительно должен быть приведён к каноническому виду:
     * trim + lowercase.
     */
    @EntityGraph(attributePaths = {
            "roles",
            "organization"
    })
    @Query("""
            select distinct user
            from UserEntity user
            where lower(user.email) = lower(:email)
            """)
    Optional<UserEntity> findByEmailIgnoreCase(
            @Param("email") String email
    );

    @Query("""
            select count(user) > 0
            from UserEntity user
            where lower(user.email) = lower(:email)
            """)
    boolean existsByEmailIgnoreCase(
            @Param("email") String email
    );

    /**
     * Загружает полный пользовательский snapshot без блокировки.

     * Используется для обычного чтения:
     * - GET /users;
     * - GET /auth/me;
     * - отображение административных данных.
     */
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

    /**
     * Единая точка pessimistic locking для security state пользователя.

     * Используется при:
     * - создании login session;
     * - смене пароля;
     * - reset password;
     * - смене email;
     * - изменении ролей;
     * - включении и отключении пользователя.

     * Запрос намеренно не содержит fetch join и collection join.
     * Organization и roles загружаются после получения user lock
     * внутри той же транзакции.

     * Единый порядок security-операций:

     * lock user
     * → проверить актуальное состояние
     * → изменить security state
     * → увеличить tokenVersion
     * → отозвать refresh sessions
     * → commit
     */
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
    Page<UUID> findAllIds(
            Pageable pageable
    );

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

    /**
     * Блокирует активных администраторов организации
     * в стабильном порядке UUID.

     * Используется для защиты инварианта:
     * организация не должна остаться без активного ADMIN.
     */
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
            select user.id
            from UserEntity user
            where user.organization.id = :organizationId
            """)
    List<UUID> findIdsByOrganizationId(
            @Param("organizationId") UUID organizationId
    );

    /**
     * Инвалидирует access JWT всех пользователей организации.

     * Метод обычно вызывается после блокировки OrganizationEntity.
     * После bulk update нельзя считать ранее загруженные UserEntity
     * актуальными без refresh/clear persistence context.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update UserEntity user
            set user.tokenVersion = user.tokenVersion + 1
            where user.organization.id = :organizationId
            """)
    int incrementTokenVersionByOrganizationId(
            @Param("organizationId") UUID organizationId
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

    long countByOrganization_Id(
            UUID organizationId
    );

    long countByOrganization_IdAndEnabled(
            UUID organizationId,
            boolean enabled
    );

    long countByEnabled(
            boolean enabled
    );

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