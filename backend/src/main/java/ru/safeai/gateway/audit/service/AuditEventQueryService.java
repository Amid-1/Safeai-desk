package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventQueryService {

    private final AuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(Pageable pageable) {
        return auditEventRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findByUserId(UUID userId, Pageable pageable) {
        return auditEventRepository.findByUser_IdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toResponse);
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