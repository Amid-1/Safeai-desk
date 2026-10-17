package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventCriteria;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    /** Hard upper bound on OFFSET rows scanned for legacy numbered pages. */
    private static final long MAX_AUDIT_OFFSET = 10_000L;

    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
    );

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of(
            "createdAt", "eventType", "organizationId", "id"
    );

    private final AuditEventRepository repository;

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            Pageable pageable
    ) {
        AuditEventQueryPolicy.QueryScope scope =
                AuditEventQueryPolicy.resolve(currentUser, filter);
        // Reject expensive pages BEFORE any repository access.
        Pageable safePageable = sanitizeAuditPageable(pageable);
        Specification<AuditEventEntity> specification =
                AuditEventCriteria.specification(scope.organizationId(), scope.filter());
        return repository.findAll(specification, safePageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findByUserId(
            UUID userId,
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(userId, "userId не должен быть null");
        return findAll(
                currentUser,
                new AuditEventFilter(null, null, userId, null, null, null),
                pageable
        );
    }

    private Pageable sanitizeAuditPageable(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE, DEFAULT_SORT);
        }
        int page = pageable.getPageNumber();
        int size = Math.clamp(pageable.getPageSize(), 1, MAX_PAGE_SIZE);
        if (page < 0) {
            throw new BadRequestException("page не может быть отрицательным");
        }
        long offset = (long) page * size;
        if (offset > MAX_AUDIT_OFFSET) {
            throw new BadRequestException(
                    "Слишком глубокая OFFSET-пагинация аудита; используйте /api/admin/audit-events/cursor"
            );
        }
        return PageRequest.of(page, size, sanitizeSort(pageable.getSort()));
    }

    private Sort sanitizeSort(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return DEFAULT_SORT;
        }
        List<String> unsupported = sort.stream()
                .map(Sort.Order::getProperty)
                .filter(property -> !ALLOWED_SORT_PROPERTIES.contains(property))
                .distinct()
                .toList();
        if (!unsupported.isEmpty()) {
            throw new BadRequestException(
                    "Сортировка по полю не разрешена: " + String.join(", ", unsupported)
            );
        }
        List<Sort.Order> orders = sort.stream()
                .map(order -> new Sort.Order(
                        order.getDirection(), order.getProperty(), order.getNullHandling()))
                .toList();
        boolean containsId = orders.stream()
                .anyMatch(order -> "id".equals(order.getProperty()));
        if (containsId) {
            return Sort.by(orders);
        }
        List<Sort.Order> deterministic = new ArrayList<>(orders);
        deterministic.add(Sort.Order.desc("id"));
        return Sort.by(deterministic);
    }

    /** Shared response mapping for offset and cursor endpoints. */
    public AuditEventResponse toResponse(AuditEventEntity entity) {
        Objects.requireNonNull(entity, "entity не должен быть null");
        return new AuditEventResponse(
                entity.getId(),
                entity.getActorUserId(),
                entity.getActorOrganizationId(),
                entity.getOrganizationId(),
                entity.getTargetOrganizationName(),
                entity.getActorEmail(),
                entity.getActorDisplayName(),
                entity.getEventType(),
                entity.getDetails(),
                entity.getCreatedAt()
        );
    }
}
