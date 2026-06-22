package ru.safeai.gateway.audit.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.util.UUID;

public interface AuditEventRepository extends
        JpaRepository<AuditEventEntity, UUID>,
        JpaSpecificationExecutor<AuditEventEntity> {
}