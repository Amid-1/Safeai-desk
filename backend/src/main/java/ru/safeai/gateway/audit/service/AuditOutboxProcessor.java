package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.config.AuditOutboxProperties;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditOutboxProcessor {

    private final AuditOutboxRepository auditOutboxRepository;
    private final AuditEventRepository auditEventRepository;
    private final AuditOutboxProperties properties;

    @Transactional
    public int processBatch() {
        List<AuditOutboxEntity> batch = auditOutboxRepository
                .findBatchForUpdate(properties.effectiveBatchSize());

        for (AuditOutboxEntity item : batch) {
            auditEventRepository.insertFromOutbox(item.getId());
            auditOutboxRepository.delete(item);
        }

        return batch.size();
    }
}
