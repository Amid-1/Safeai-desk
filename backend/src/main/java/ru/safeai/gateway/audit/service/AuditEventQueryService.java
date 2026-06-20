package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventQueryService {

    private final AuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        if (isSuperAdmin(currentUser)) {
            return auditEventRepository.findAllByOrderByCreatedAtDesc(pageable)
                    .map(this::toResponse);
        }

        return auditEventRepository
                .findByOrganizationIdOrderByCreatedAtDesc(
                        currentUser.getOrganizationId(),
                        pageable
                )
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findByUserId(
            UUID userId,
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        if (isSuperAdmin(currentUser)) {
            return auditEventRepository
                    .findByUser_IdOrderByCreatedAtDesc(userId, pageable)
                    .map(this::toResponse);
        }

        return auditEventRepository
                .findByUser_IdAndOrganizationIdOrderByCreatedAtDesc(
                        userId,
                        currentUser.getOrganizationId(),
                        pageable
                )
                .map(this::toResponse);
    }

    private AuditEventResponse toResponse(AuditEventEntity entity) {
        UUID userId = entity.getUser() == null ? null : entity.getUser().getId();
        String userEmail = entity.getUser() == null ? null : entity.getUser().getEmail();

        return new AuditEventResponse(
                entity.getId(),
                userId,
                entity.getOrganizationId(),
                userEmail,
                entity.getEventType(),
                entity.getDetails(),
                entity.getCreatedAt()
        );
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }
}