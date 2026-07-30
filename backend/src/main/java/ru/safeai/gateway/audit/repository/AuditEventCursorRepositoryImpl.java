package ru.safeai.gateway.audit.repository;

import jakarta.persistence.EntityManager;
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
import java.util.UUID;

@RequiredArgsConstructor
public class AuditEventCursorRepositoryImpl
        implements AuditEventCursorRepository {

    private final EntityManager entityManager;

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
