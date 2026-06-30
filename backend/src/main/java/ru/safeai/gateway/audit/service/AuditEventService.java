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
    public void recordSystem(
            UUID organizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(null, organizationId, eventType, details);
    }


    /**
     * SECURITY NOTICE

     * Audit details must NEVER contain:

     * - passwords
     * - refresh tokens
     * - access tokens
     * - API keys
     * - Authorization headers
     * - cookies
     * - AI prompts
     * - AI responses

     * Audit is intended only for security metadata.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID userId,
            UUID organizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        try {
            if (organizationId == null) {
                throw new IllegalArgumentException("organizationId не должен быть null для audit event");
            }

            UserEntity user = null;

            if (userId != null) {
                user = entityManager.getReference(UserEntity.class, userId);
            }

            AuditEventEntity event = new AuditEventEntity();
            event.setUser(user);
            event.setOrganizationId(organizationId);
            event.setEventType(eventType.name());
            event.setDetails(details == null ? Map.of() : Map.copyOf(details));

            auditEventRepository.save(event);
        } catch (Exception exception) {
            log.error(
                    "Не удалось записать событие аудита: userId={}, organizationId={}, eventType={}",
                    userId,
                    organizationId,
                    eventType,
                    exception
            );
        }
    }
}