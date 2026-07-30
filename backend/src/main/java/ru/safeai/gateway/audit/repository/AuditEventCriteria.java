package ru.safeai.gateway.audit.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AuditEventCriteria {

    private AuditEventCriteria() {
    }

    public static Specification<AuditEventEntity> specification(
            UUID organizationId,
            AuditEventFilter filter
    ) {
        Objects.requireNonNull(
                filter,
                "filter не должен быть null"
        );

        return (root, query, cb) -> {
            List<Predicate> predicates =
                    predicates(
                            root,
                            cb,
                            organizationId,
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

    public static List<Predicate> predicates(
            Root<AuditEventEntity> root,
            CriteriaBuilder cb,
            UUID organizationId,
            AuditEventFilter filter
    ) {
        Objects.requireNonNull(
                root,
                "root не должен быть null"
        );
        Objects.requireNonNull(
                cb,
                "cb не должен быть null"
        );
        Objects.requireNonNull(
                filter,
                "filter не должен быть null"
        );

        List<Predicate> predicates =
                new ArrayList<>();

        if (organizationId != null) {
            predicates.add(
                    cb.equal(
                            root.get("organizationId"),
                            organizationId
                    )
            );
        }

        if (filter.eventType() != null) {
            predicates.add(
                    cb.equal(
                            root.get("eventType"),
                            filter.eventType().name()
                    )
            );
        }

        if (filter.userId() != null) {
            predicates.add(
                    cb.equal(
                            root.get("actorUserId"),
                            filter.userId()
                    )
            );
        }

        if (filter.userEmail() != null) {
            predicates.add(
                    cb.like(
                            root.get("actorEmail"),
                            escapeLike(filter.userEmail())
                                    + "%",
                            '\\'
                    )
            );
        }

        Path<Instant> createdAt =
                root.get("createdAt");

        if (filter.dateFrom() != null) {
            predicates.add(
                    cb.greaterThanOrEqualTo(
                            createdAt,
                            filter.dateFrom()
                    )
            );
        }

        if (filter.dateTo() != null) {
            predicates.add(
                    cb.lessThan(
                            createdAt,
                            filter.dateTo()
                    )
            );
        }

        return predicates;
    }

    public static Predicate beforeCursor(
            Root<AuditEventEntity> root,
            CriteriaBuilder cb,
            Instant beforeCreatedAt,
            UUID beforeId
    ) {
        Objects.requireNonNull(
                root,
                "root не должен быть null"
        );
        Objects.requireNonNull(
                cb,
                "cb не должен быть null"
        );
        Objects.requireNonNull(
                beforeCreatedAt,
                "beforeCreatedAt не должен быть null"
        );
        Objects.requireNonNull(
                beforeId,
                "beforeId не должен быть null"
        );

        Path<Instant> createdAt =
                root.get("createdAt");

        Path<UUID> id = root.get("id");

        return cb.or(
                cb.lessThan(
                        createdAt,
                        beforeCreatedAt
                ),
                cb.and(
                        cb.equal(
                                createdAt,
                                beforeCreatedAt
                        ),
                        cb.lessThan(id, beforeId)
                )
        );
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
