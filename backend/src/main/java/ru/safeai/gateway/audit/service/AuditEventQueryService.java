package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventQueryService {

    private final AuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public List<AuditEventResponse> findAll() {
        return auditEventRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> findByUserId(UUID userId) {
        return auditEventRepository.findByUser_IdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private AuditEventResponse toResponse(AuditEventEntity entity) {
        UUID userId = entity.getUser() == null ? null : entity.getUser().getId();
        String userEmail = entity.getUser() == null ? null : entity.getUser().getEmail();

        return new AuditEventResponse(
                entity.getId(),
                userId,
                userEmail,
                entity.getEventType(),
                entity.getDetails(),
                entity.getCreatedAt()
        );
    }
}