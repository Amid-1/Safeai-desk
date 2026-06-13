package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, String eventType, Map<String, Object> details) {
        UserEntity user = null;

        if (userId != null) {
            user = userRepository.findById(userId).orElse(null);
        }

        AuditEventEntity event = new AuditEventEntity();
        event.setUser(user);
        event.setEventType(eventType);
        event.setDetails(details == null ? Map.of() : details);

        auditEventRepository.save(event);
    }
}