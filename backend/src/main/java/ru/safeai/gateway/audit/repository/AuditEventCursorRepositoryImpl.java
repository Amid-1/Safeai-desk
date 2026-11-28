package ru.safeai.gateway.audit.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public class AuditEventCursorRepositoryImpl
        implements AuditEventCursorRepository {

    private final EntityManager entityManager;

    @Override
    public Map<String, Long> countByEventType(UUID organizationId, AuditEventFilter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<AuditEventEntity> root = query.from(AuditEventEntity.class);
        var type = root.<String>get("eventType");
        query.multiselect(type, cb.count(root));
        query.where(AuditEventCriteria.predicates(root, cb, organizationId, filter)
                .toArray(Predicate[]::new));
        query.groupBy(type);
        Map<String, Long> result = new HashMap<>();
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            result.put(row.get(0, String.class), row.get(1, Long.class));
        }
        return result;
    }

    @Override
    public List<AuditEventEntity> findByCursor(
            UUID organizationId,
            AuditEventFilter filter,
            Instant beforeCreatedAt,
            UUID beforeId,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit должен быть положительным"
            );
        }

        boolean hasCursor =
                beforeCreatedAt != null
                        || beforeId != null;

        if (hasCursor
                && (beforeCreatedAt == null
                || beforeId == null)) {
            throw new IllegalArgumentException(
                    "beforeCreatedAt и beforeId "
                            + "должны передаваться вместе"
            );
        }

        CriteriaBuilder cb =
                entityManager.getCriteriaBuilder();

        CriteriaQuery<AuditEventEntity> query =
                cb.createQuery(AuditEventEntity.class);

        Root<AuditEventEntity> root =
                query.from(AuditEventEntity.class);

        List<Predicate> predicates =
                AuditEventCriteria.predicates(
                        root,
                        cb,
                        organizationId,
                        filter
                );

        if (hasCursor) {
            predicates.add(
                    AuditEventCriteria.beforeCursor(
                            root,
                            cb,
                            beforeCreatedAt,
                            beforeId
                    )
            );
        }

        query.select(root);

        if (!predicates.isEmpty()) {
            query.where(
                    cb.and(
                            predicates.toArray(
                                    Predicate[]::new
                            )
                    )
            );
        }

        query.orderBy(
                cb.desc(root.get("createdAt")),
                cb.desc(root.get("id"))
        );

        TypedQuery<AuditEventEntity> typedQuery =
                entityManager.createQuery(query);

        typedQuery.setMaxResults(limit);

        return typedQuery.getResultList();
    }
}
