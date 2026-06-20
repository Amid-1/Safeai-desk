package ru.safeai.gateway.audit.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    @EntityGraph(attributePaths = "user")
    Page<AuditEventEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<AuditEventEntity> findByOrganizationIdOrderByCreatedAtDesc(
            UUID organizationId,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "user")
    Page<AuditEventEntity> findByUser_IdOrderByCreatedAtDesc(
            UUID userId,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "user")
    Page<AuditEventEntity> findByUser_IdAndOrganizationIdOrderByCreatedAtDesc(
            UUID userId,
            UUID organizationId,
            Pageable pageable
    );
}