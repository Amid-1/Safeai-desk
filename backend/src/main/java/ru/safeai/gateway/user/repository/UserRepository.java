package ru.safeai.gateway.user.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    @Query("""
        select count(u)
        from UserEntity u
        join u.roles r
        where u.enabled = true
          and r.name = 'ADMIN'
        """)
    long countEnabledAdmins();

    @EntityGraph(attributePaths = {"roles", "organization"})
    @Query("""
    select distinct u
    from UserEntity u
    where u.organization.id = :organizationId
    order by u.createdAt desc
    """)
    List<UserEntity> findAllByOrganizationIdWithRoles(UUID organizationId);
}