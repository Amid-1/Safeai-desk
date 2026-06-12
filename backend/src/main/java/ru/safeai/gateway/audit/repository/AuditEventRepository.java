package ru.safeai.gateway.audit.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    @EntityGraph(attributePaths = "user")
    List<AuditEventEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    @EntityGraph(attributePaths = "user")
    List<AuditEventEntity> findAllByOrderByCreatedAtDesc();
}