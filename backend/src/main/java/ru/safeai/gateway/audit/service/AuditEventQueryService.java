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

    private final AuditEventRepository repository;

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter,
            Pageable pageable
    ) {
        AuditEventQueryPolicy.QueryScope scope =
                AuditEventQueryPolicy.resolve(
                        currentUser,
                        filter
                );

        Specification<AuditEventEntity> specification =
                AuditEventCriteria.specification(
                        scope.organizationId(),
                        scope.filter()
                );

        return repository
                .findAll(
                        specification,
                        sanitizeAuditPageable(pageable)
                )
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findByUserId(
            UUID userId,
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        if (userId == null) {
            throw new NullPointerException(
                    "userId не должен быть null"
            );
        }

        return findAll(
                currentUser,
                new AuditEventFilter(
                        null,
                        null,
                        userId,
                        null,
                        null,
                        null
                ),
                pageable
        );
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

        return PageRequest.of(
                Math.max(
                        pageable.getPageNumber(),
                        0
                ),
                Math.clamp(
                        pageable.getPageSize(),
                        1,
                        MAX_PAGE_SIZE
                ),
                sanitizeSort(pageable.getSort())
        );
    }

    private Sort sanitizeSort(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return DEFAULT_SORT;
        }

        List<String> unsupported = sort.stream()
                .map(Sort.Order::getProperty)
                .filter(property ->
                        !ALLOWED_SORT_PROPERTIES
                                .contains(property)
                )
                .distinct()
                .toList();

        if (!unsupported.isEmpty()) {
            throw new BadRequestException(
                    "Сортировка по полю не разрешена: "
                            + String.join(
                                    ", ",
                                    unsupported
                            )
            );
        }

        List<Sort.Order> orders = sort.stream()
                .map(order ->
                        new Sort.Order(
                                order.getDirection(),
                                order.getProperty(),
                                order.getNullHandling()
                        )
                )
                .toList();

        boolean containsId = orders.stream()
                .anyMatch(order ->
                        "id".equals(
                                order.getProperty()
                        )
                );

        if (containsId) {
            return Sort.by(orders);
        }

        List<Sort.Order> deterministic =
                new ArrayList<>(orders);

        deterministic.add(
                Sort.Order.desc("id")
        );

        return Sort.by(deterministic);
    }

    AuditEventResponse toResponse(
            AuditEventEntity entity
    ) {
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
