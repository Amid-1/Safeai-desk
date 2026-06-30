package ru.safeai.gateway.audit.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.ArrayList;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventQueryService {

    private final AuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            Pageable pageable
    ) {
        boolean superAdmin = isSuperAdmin(currentUser);

        return auditEventRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();

            if (!superAdmin) {
                predicates.add(cb.equal(root.get("organizationId"), currentUser.getOrganizationId()));
            }

            if (superAdmin && filter.organizationId() != null) {
                predicates.add(cb.equal(root.get("organizationId"), filter.organizationId()));
            }

            if (filter.eventType() != null) {
                predicates.add(cb.equal(
                        root.get("eventType"),
                        filter.eventType().name()
                ));
            }

            if (filter.userEmail() != null && !filter.userEmail().isBlank()) {
                var user = root.join("user", JoinType.LEFT);

                predicates.add(cb.like(
                        cb.lower(user.get("email")),
                        "%" + filter.userEmail().trim().toLowerCase() + "%"
                ));
            }

            if (filter.userId() != null) {
                var user = root.join("user", JoinType.LEFT);
                predicates.add(cb.equal(user.get("id"), filter.userId()));
            }

            if (filter.dateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.dateFrom()));
            }

            if (filter.dateTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.dateTo()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        }, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findByUserId(
            UUID userId,
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        boolean superAdmin = isSuperAdmin(currentUser);

        return auditEventRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();

            var user = root.join("user", JoinType.LEFT);
            predicates.add(cb.equal(user.get("id"), userId));

            if (!superAdmin) {
                predicates.add(cb.equal(root.get("organizationId"), currentUser.getOrganizationId()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        }, pageable).map(this::toResponse);
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