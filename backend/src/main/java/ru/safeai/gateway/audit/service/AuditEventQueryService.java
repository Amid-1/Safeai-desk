package ru.safeai.gateway.audit.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
    );

    private static final Set<String> ALLOWED_SORT_PROPERTIES =
            Set.of(
                    "createdAt",
                    "eventType",
                    "organizationId",
                    "id"
            );

    private final AuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            Pageable pageable
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        AuditEventFilter effectiveFilter =
                filter == null
                        ? emptyFilter()
                        : filter;

        validateDateRange(effectiveFilter);

        boolean superAdmin =
                isSuperAdmin(currentUser);

        validateOrganizationFilter(
                currentUser,
                effectiveFilter,
                superAdmin
        );

        Pageable safePageable =
                sanitizeAuditPageable(pageable);

        Specification<AuditEventEntity> specification =
                buildSpecification(
                        currentUser,
                        effectiveFilter,
                        superAdmin
                );

        return auditEventRepository
                .findAll(
                        specification,
                        safePageable
                )
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findByUserId(
            UUID userId,
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        AuditEventFilter filter =
                new AuditEventFilter(
                        null,
                        null,
                        userId,
                        null,
                        null,
                        null
                );

        return findAll(
                currentUser,
                filter,
                pageable
        );
    }

    private Specification<AuditEventEntity> buildSpecification(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            boolean superAdmin
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates =
                    new ArrayList<>();

            addOrganizationPredicate(
                    root,
                    cb,
                    predicates,
                    currentUser,
                    filter,
                    superAdmin
            );

            addEventTypePredicate(
                    root,
                    cb,
                    predicates,
                    filter
            );

            addUserPredicates(
                    root,
                    cb,
                    predicates,
                    filter
            );

            addDatePredicates(
                    root,
                    cb,
                    predicates,
                    filter
            );

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(
                    predicates.toArray(
                            Predicate[]::new
                    )
            );
        };
    }

    private void addOrganizationPredicate(
            Root<AuditEventEntity> root,
            CriteriaBuilder cb,
            List<Predicate> predicates,
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            boolean superAdmin
    ) {
        if (!superAdmin) {
            predicates.add(
                    cb.equal(
                            root.get("organizationId"),
                            currentUser.getOrganizationId()
                    )
            );

            return;
        }

        if (filter.organizationId() != null) {
            predicates.add(
                    cb.equal(
                            root.get("organizationId"),
                            filter.organizationId()
                    )
            );
        }
    }

    private void addEventTypePredicate(
            Root<AuditEventEntity> root,
            CriteriaBuilder cb,
            List<Predicate> predicates,
            AuditEventFilter filter
    ) {
        if (filter.eventType() == null) {
            return;
        }

        predicates.add(
                cb.equal(
                        root.get("eventType"),
                        filter.eventType().name()
                )
        );
    }

    private void addUserPredicates(
            Root<AuditEventEntity> root,
            CriteriaBuilder cb,
            List<Predicate> predicates,
            AuditEventFilter filter
    ) {
        boolean hasUserId =
                filter.userId() != null;

        boolean hasUserEmail =
                filter.userEmail() != null
                        && !filter.userEmail().isBlank();

        if (!hasUserId && !hasUserEmail) {
            return;
        }

        if (hasUserId) {
            predicates.add(
                    cb.equal(
                            root.get("actorUserId"),
                            filter.userId()
                    )
            );
        }

        if (hasUserEmail) {
            String normalizedEmailPrefix =
                    escapeLike(
                            filter.userEmail()
                                    .trim()
                                    .toLowerCase(Locale.ROOT)
                    );

            predicates.add(
                    cb.like(
                            cb.lower(
                                    root.get("actorEmail")
                            ),
                            normalizedEmailPrefix + "%",
                            '\\'
                    )
            );
        }
    }

    private void addDatePredicates(
            Root<AuditEventEntity> root,
            CriteriaBuilder cb,
            List<Predicate> predicates,
            AuditEventFilter filter
    ) {
        if (filter.dateFrom() != null) {
            predicates.add(
                    cb.greaterThanOrEqualTo(
                            root.get("createdAt"),
                            filter.dateFrom()
                    )
            );
        }

        if (filter.dateTo() != null) {
            predicates.add(
                    cb.lessThan(
                            root.get("createdAt"),
                            filter.dateTo()
                    )
            );
        }
    }

    private void validateOrganizationFilter(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            boolean superAdmin
    ) {
        if (superAdmin
                || filter.organizationId() == null) {
            return;
        }

        if (!filter.organizationId().equals(
                currentUser.getOrganizationId()
        )) {
            throw new ForbiddenOperationException(
                    "Нельзя фильтровать аудит другой организации"
            );
        }
    }

    private Pageable sanitizeAuditPageable(
            Pageable pageable
    ) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(
                    0,
                    DEFAULT_PAGE_SIZE,
                    DEFAULT_SORT
            );
        }

        int pageNumber =
                Math.max(
                        pageable.getPageNumber(),
                        0
                );

        int pageSize = Math.clamp(
                pageable.getPageSize(),
                1,
                MAX_PAGE_SIZE
        );

        return PageRequest.of(
                pageNumber,
                pageSize,
                sanitizeSort(
                        pageable.getSort()
                )
        );
    }

    private Sort sanitizeSort(
            Sort sort
    ) {
        if (sort == null || sort.isUnsorted()) {
            return DEFAULT_SORT;
        }

        List<Sort.Order> allowedOrders =
                sort.stream()
                        .filter(order ->
                                ALLOWED_SORT_PROPERTIES
                                        .contains(
                                                order.getProperty()
                                        )
                        )
                        .map(order ->
                                new Sort.Order(
                                        order.getDirection(),
                                        order.getProperty(),
                                        order.getNullHandling()
                                )
                        )
                        .toList();

        if (allowedOrders.isEmpty()) {
            return DEFAULT_SORT;
        }

        boolean containsId =
                allowedOrders.stream()
                        .anyMatch(order ->
                                "id".equals(
                                        order.getProperty()
                                )
                        );

        if (containsId) {
            return Sort.by(allowedOrders);
        }

        List<Sort.Order> deterministic =
                new ArrayList<>(allowedOrders);

        deterministic.add(
                Sort.Order.desc("id")
        );

        return Sort.by(deterministic);
    }

    private void validateDateRange(
            AuditEventFilter filter
    ) {
        if (filter.dateFrom() != null
                && filter.dateTo() != null
                && !filter.dateFrom().isBefore(
                filter.dateTo()
        )) {
            throw new BadRequestException(
                    "dateFrom должен быть раньше dateTo"
            );
        }
    }

    private String escapeLike(
            String value
    ) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private AuditEventFilter emptyFilter() {
        return new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private AuditEventResponse toResponse(
            AuditEventEntity entity
    ) {
        return new AuditEventResponse(
                entity.getId(),
                entity.getActorUserId(),
                entity.getOrganizationId(),
                entity.getActorEmail(),
                entity.getActorDisplayName(),
                entity.getEventType(),
                entity.getDetails(),
                entity.getCreatedAt()
        );
    }

    private boolean isSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return currentUser
                .authorityNames()
                .contains(
                        SystemRole.SUPER_ADMIN.authority()
                );
    }
}