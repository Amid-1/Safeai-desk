package ru.safeai.gateway.audit.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.user.entity.UserEntity;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, AuditEventType eventType, Map<String, Object> details) {
        try {
            UserEntity user = null;

            if (userId != null) {
                user = entityManager.getReference(UserEntity.class, userId);
            }

            AuditEventEntity event = new AuditEventEntity();
            event.setUser(user);
            event.setEventType(eventType.name());
            event.setDetails(details == null ? Map.of() : details);

            auditEventRepository.save(event);
        } catch (Exception exception) {
            log.error("Failed to write audit event: userId={}, eventType={}", userId, eventType, exception);
        }
    }
}