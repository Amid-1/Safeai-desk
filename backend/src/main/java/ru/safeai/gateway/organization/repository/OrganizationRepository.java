package ru.safeai.gateway.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    @Query(
            value = """
                select exists (
                    select 1
                    from public.organizations
                    where normalized_name = :normalizedName
                )
                """,
            nativeQuery = true
    )
    boolean existsByNormalizedName(
            @Param("normalizedName")
            String normalizedName
    );

    @Query(
            value = """
                select exists (
                    select 1
                    from public.organizations
                    where normalized_name = :normalizedName
                      and id <> :excludedId
                )
                """,
            nativeQuery = true
    )
    boolean existsByNormalizedNameAndIdNot(
            @Param("normalizedName")
            String normalizedName,
            @Param("excludedId")
            UUID excludedId
    );
}