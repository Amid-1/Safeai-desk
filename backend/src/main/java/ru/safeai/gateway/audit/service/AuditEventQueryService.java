package ru.safeai.gateway.audit.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;

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

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Direction.DESC, "createdAt");

    private static final Set<String> ALLOWED_SORT_PROPERTIES =
            Set.of(
                    "createdAt",
                    "eventType",
                    "organizationId"
            );

    private final AuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            Pageable pageable
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        AuditEventFilter effectiveFilter = filter == null
                ? emptyFilter()
                : filter;

        Pageable safePageable = sanitizeAuditPageable(pageable);
        boolean superAdmin = isSuperAdmin(currentUser);

        validateOrganizationFilter(
                currentUser,
                effectiveFilter,
                superAdmin
        );

        return auditEventRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            addOrganizationPredicate(
                    root,
                    cb,
                    predicates,
                    currentUser,
                    effectiveFilter,
                    superAdmin
            );

            addEventTypePredicate(
                    root,
                    cb,
                    predicates,
                    effectiveFilter
            );

            addUserPredicates(
                    root,
                    cb,
                    predicates,
                    effectiveFilter
            );

            addDatePredicates(
                    root,
                    cb,
                    predicates,
                    effectiveFilter
            );

            return cb.and(predicates.toArray(new Predicate[0]));
        }, safePageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findByUserId(
            UUID userId,
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(userId, "userId не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        Pageable safePageable = sanitizeAuditPageable(pageable);
        boolean superAdmin = isSuperAdmin(currentUser);

        return auditEventRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            Join<AuditEventEntity, UserEntity> userJoin = root.join("user", JoinType.LEFT);

            predicates.add(cb.equal(
                    userJoin.get("id"),
                    userId
            ));

            if (!superAdmin) {
                predicates.add(cb.equal(
                        root.get("organizationId"),
                        currentUser.getOrganizationId()
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        }, safePageable).map(this::toResponse);
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
            predicates.add(cb.equal(
                    root.get("organizationId"),
                    currentUser.getOrganizationId()
            ));
            return;
        }

        if (filter.organizationId() != null) {
            predicates.add(cb.equal(
                    root.get("organizationId"),
                    filter.organizationId()
            ));
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

        predicates.add(cb.equal(
                root.get("eventType"),
                filter.eventType().name()
        ));
    }

    private void addUserPredicates(
            Root<AuditEventEntity> root,
            CriteriaBuilder cb,
            List<Predicate> predicates,
            AuditEventFilter filter
    ) {
        boolean hasUserEmail = filter.userEmail() != null
                && !filter.userEmail().isBlank();

        boolean hasUserId = filter.userId() != null;

        if (!hasUserEmail && !hasUserId) {
            return;
        }

        Join<AuditEventEntity, UserEntity> userJoin = root.join("user", JoinType.LEFT);

        if (hasUserEmail) {
            String normalizedEmail = filter.userEmail()
                    .trim()
                    .toLowerCase();

            predicates.add(cb.like(
                    cb.lower(userJoin.get("email")),
                    "%" + normalizedEmail + "%"
            ));
        }

        if (hasUserId) {
            predicates.add(cb.equal(
                    userJoin.get("id"),
                    filter.userId()
            ));
        }
    }

    private void addDatePredicates(
            Root<AuditEventEntity> root,
            CriteriaBuilder cb,
            List<Predicate> predicates,
            AuditEventFilter filter
    ) {
        if (filter.dateFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(
                    root.get("createdAt"),
                    filter.dateFrom()
            ));
        }

        if (filter.dateTo() != null) {
            predicates.add(cb.lessThan(
                    root.get("createdAt"),
                    filter.dateTo()
            ));
        }
    }

    private void validateOrganizationFilter(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            boolean superAdmin
    ) {
        if (superAdmin || filter.organizationId() == null) {
            return;
        }

        if (!filter.organizationId().equals(currentUser.getOrganizationId())) {
            throw new ForbiddenOperationException(
                    "Нельзя фильтровать audit другой организации"
            );
        }
    }

    private Pageable sanitizeAuditPageable(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(
                    0,
                    DEFAULT_PAGE_SIZE,
                    DEFAULT_SORT
            );
        }

        int pageSize = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);

        Sort safeSort = sanitizeSort(pageable.getSort());

        return PageRequest.of(
                pageable.getPageNumber(),
                pageSize,
                safeSort
        );
    }

    private Sort sanitizeSort(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return DEFAULT_SORT;
        }

        List<Sort.Order> allowedOrders = sort.stream()
                .filter(order -> ALLOWED_SORT_PROPERTIES.contains(order.getProperty()))
                .map(order -> new Sort.Order(
                        order.getDirection(),
                        order.getProperty(),
                        order.getNullHandling()
                ))
                .toList();

        if (allowedOrders.isEmpty()) {
            return DEFAULT_SORT;
        }

        return Sort.by(allowedOrders);
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