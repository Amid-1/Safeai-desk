package ru.safeai.gateway.audit.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    List<AuditEventEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<AuditEventEntity> findAllByOrderByCreatedAtDesc();
}