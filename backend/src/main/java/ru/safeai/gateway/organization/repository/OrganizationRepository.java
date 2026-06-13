package ru.safeai.gateway.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.organization.entity.OrganizationEntity;

import java.util.List;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    boolean existsByNameIgnoreCase(String name);

    List<OrganizationEntity> findAllByOrderByCreatedAtDesc();
}