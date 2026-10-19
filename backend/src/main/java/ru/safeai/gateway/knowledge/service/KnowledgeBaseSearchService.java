package ru.safeai.gateway.knowledge.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseResponse;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseMembershipEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side, tenant-scoped filtering BEFORE pagination.
 * Does not change the existing KnowledgeBaseService listing or ACL endpoints.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseSearchService {

    private static final int MAX_PAGE_SIZE = 100;

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page<KnowledgeBaseResponse> search(
            SafeAiUserPrincipal user,
            String query,
            Boolean enabled,
            KnowledgeBaseVisibility visibility,
            int page,
            int size
    ) {
        Objects.requireNonNull(user, "currentUser не должен быть null");
        boolean admin = hasAuthority(user, "ROLE_ADMIN");
        if (!admin && !hasAuthority(user, "ROLE_USER")) {
            throw new ForbiddenOperationException(
                    "Базы знаний доступны только tenant ADMIN/USER"
            );
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE
                || page > Integer.MAX_VALUE / size) {
            throw new BadRequestException("Некорректная пагинация баз знаний");
        }

        String namePattern = KnowledgeBaseSearchPattern.from(query);

        // SafeAiUserPrincipal is final; its constructor requires both UUIDs.
        // Tenant identity cannot be null for an authenticated principal.
        UUID organizationId = user.getOrganizationId();
        UUID userId = user.getId();

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<KnowledgeBaseEntity> selection =
                cb.createQuery(KnowledgeBaseEntity.class);
        Root<KnowledgeBaseEntity> kb = selection.from(KnowledgeBaseEntity.class);
        selection.select(kb).where(restrictions(
                cb, selection, kb, organizationId, userId,
                admin, namePattern, enabled, visibility
        ));
        selection.orderBy(
                cb.desc(kb.get("updatedAt")),
                cb.desc(kb.get("id"))
        );

        TypedQuery<KnowledgeBaseEntity> rows = entityManager.createQuery(selection);
        rows.setFirstResult(page * size);
        rows.setMaxResults(size);

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<KnowledgeBaseEntity> counted = count.from(KnowledgeBaseEntity.class);
        count.select(cb.count(counted)).where(restrictions(
                cb, count, counted, organizationId, userId,
                admin, namePattern, enabled, visibility
        ));

        long total = entityManager.createQuery(count).getSingleResult();
        List<KnowledgeBaseResponse> content = rows.getResultList()
                .stream().map(KnowledgeBaseResponse::from).toList();

        PageRequest pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("updatedAt"),
                Sort.Order.desc("id")
        ));
        return new PageImpl<>(content, pageable, total);
    }

    private static Predicate restrictions(
            CriteriaBuilder cb,
            CommonAbstractCriteria query,
            Root<KnowledgeBaseEntity> kb,
            UUID organizationId,
            UUID userId,
            boolean admin,
            String namePattern,
            Boolean enabled,
            KnowledgeBaseVisibility visibility
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(kb.get("organizationId"), organizationId));

        if (namePattern != null) {
            predicates.add(cb.like(cb.lower(kb.get("name")), namePattern, '!'));
        }
        if (enabled != null) {
            predicates.add(cb.equal(kb.get("enabled"), enabled));
        }
        if (visibility != null) {
            predicates.add(cb.equal(kb.get("visibility"), visibility));
        }

        if (!admin) {
            // USER never sees disabled KBs, even with enabled=false as a filter.
            predicates.add(cb.isTrue(kb.get("enabled")));

            Subquery<Integer> membership = query.subquery(Integer.class);
            Root<KnowledgeBaseMembershipEntity> member =
                    membership.from(KnowledgeBaseMembershipEntity.class);
            membership.select(cb.literal(1)).where(
                    cb.equal(member.get("knowledgeBaseId"), kb.get("id")),
                    cb.equal(member.get("organizationId"), organizationId),
                    cb.equal(member.get("userId"), userId)
            );
            predicates.add(cb.or(
                    cb.equal(kb.get("visibility"), KnowledgeBaseVisibility.ORGANIZATION),
                    cb.exists(membership)
            ));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }

    private static boolean hasAuthority(SafeAiUserPrincipal user, String role) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }
}
