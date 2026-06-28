package ru.safeai.gateway.user.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.user.entity.UserEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    @EntityGraph(attributePaths = {"roles", "organization"})
    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdWithRolesAndOrganization(UUID id);

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query(
            value = """
                    select distinct u
                    from UserEntity u
                    """,
            countQuery = """
                    select count(u)
                    from UserEntity u
                    """
    )
    Page<UserEntity> findAllWithRolesAndOrganization(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select distinct u
            from UserEntity u
            join u.roles r
            where u.organization.id = :organizationId
              and u.enabled = true
              and r.name = 'ADMIN'
            """)
    List<UserEntity> findEnabledAdminsForUpdate(UUID organizationId);

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query("""
            select u
            from UserEntity u
            where u.id = :id
              and u.organization.id = :organizationId
            """)
    Optional<UserEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query(
            value = """
                    select distinct u
                    from UserEntity u
                    where u.organization.id = :organizationId
                    """,
            countQuery = """
                    select count(u)
                    from UserEntity u
                    where u.organization.id = :organizationId
                    """
    )
    Page<UserEntity> findAllByOrganizationIdWithRoles(UUID organizationId, Pageable pageable);

    @EntityGraph(attributePaths = {"organization"})
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdWithOrganization(UUID id);

    @Query("""
            select u.id
            from UserEntity u
            where u.organization.id = :organizationId
            """)
    List<UUID> findIdsByOrganizationId(@Param("organizationId") UUID organizationId);
}