package ru.safeai.gateway.organization.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.organization.entity.OrganizationEntity;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository
        extends JpaRepository<OrganizationEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select organization
            from OrganizationEntity organization
            where organization.id = :id
            """)
    Optional<OrganizationEntity> findByIdForSecurityUpdate(
            @Param("id")
            UUID id
    );

    @Query("""
            select organization
            from OrganizationEntity organization
            """)
    Page<OrganizationEntity> findAllStable(
            Pageable pageable
    );

    @Query("""
            select organization
            from OrganizationEntity organization
            where lower(organization.name)
                  like lower(concat('%', :query, '%'))
                  escape '!'
            """)
    Page<OrganizationEntity> searchDirectoryByName(
            @Param("query")
            String query,
            Pageable pageable
    );
}
