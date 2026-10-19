package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-aware directory search. All predicates are evaluated in the database
 * BEFORE paging. The existing /api/users API and all mutation contracts stay intact.
 */
@Service
public class UserDirectorySearchService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 120;

    private final EntityManager entityManager;
    private final UserRepository repository;
    private final UserAccessPolicy accessPolicy;
    private final UserResponseMapper responseMapper = new UserResponseMapper();

    public UserDirectorySearchService(
            EntityManager entityManager,
            UserRepository repository,
            PlatformProperties platformProperties
    ) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.accessPolicy = new UserAccessPolicy(platformProperties);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<UserResponse> search(
            SafeAiUserPrincipal principal,
            String role,
            Boolean enabled,
            String search,
            int page,
            int size
    ) {
        accessPolicy.requireUserManager(principal);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE
                || page > Integer.MAX_VALUE / size) {
            throw new BadRequestException("Некорректная пагинация пользователей");
        }
        String normalizedRole = accessPolicy.normalizeListRole(role);
        String pattern = searchPattern(search);
        UUID tenantId = principal.getOrganizationId();
        boolean superAdmin = accessPolicy.isSuperAdmin(principal);

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<UUID> ids = cb.createQuery(UUID.class);
        Root<UserEntity> user = ids.from(UserEntity.class);
        ids.select(user.get("id"));
        ids.where(restrictions(cb, ids, user, tenantId, superAdmin,
                normalizedRole, enabled, pattern));
        ids.orderBy(cb.desc(user.get("createdAt")), cb.desc(user.get("id")));

        TypedQuery<UUID> rows = entityManager.createQuery(ids);
        rows.setFirstResult(page * size);
        rows.setMaxResults(size);

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<UserEntity> counted = count.from(UserEntity.class);
        count.select(cb.count(counted));
        count.where(restrictions(cb, count, counted, tenantId, superAdmin,
                normalizedRole, enabled, pattern));

        long total = entityManager.createQuery(count).getSingleResult();
        List<UUID> pageIds = rows.getResultList();
        List<UserResponse> content = new ArrayList<>(pageIds.size());
        if (!pageIds.isEmpty()) {
            // Fetch associations in a separate query: no collection join in paging.
            Map<UUID, UserEntity> found = new HashMap<>();
            for (UserEntity entity : repository.findAllByIdsWithRolesAndOrganization(pageIds)) {
                found.put(entity.getId(), entity);
            }
            for (UUID id : pageIds) {
                UserEntity entity = found.get(id);
                if (entity == null) {
                    throw new IllegalStateException("Неполный page snapshot пользователя");
                }
                // Fail closed if a future repository implementation violates scope.
                if (!superAdmin && !tenantId.equals(entity.getOrganization().getId())) {
                    throw new IllegalStateException("Нарушена tenant isolation при поиске");
                }
                content.add(responseMapper.toResponse(entity));
            }
        }
        return new PageImpl<>(content, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))), total);
    }

    private static Predicate restrictions(
            CriteriaBuilder cb,
            CommonAbstractCriteria query,
            Root<UserEntity> user,
            UUID tenantId,
            boolean superAdmin,
            String role,
            Boolean enabled,
            String pattern
    ) {
        List<Predicate> all = new ArrayList<>();
        if (!superAdmin) {
            all.add(cb.equal(user.get("organization").get("id"), tenantId));
        }
        if (enabled != null) {
            all.add(cb.equal(user.get("enabled"), enabled));
        }
        if (pattern != null) {
            all.add(cb.or(
                    cb.like(cb.lower(user.get("email")), pattern, '!'),
                    cb.like(cb.lower(user.get("fullName")), pattern, '!')
            ));
        }
        if (role != null) {
            Subquery<Integer> roleExists = query.subquery(Integer.class);
            Root<UserEntity> matched = roleExists.from(UserEntity.class);
            var roles = matched.join("roles");
            roleExists.select(cb.literal(1));
            roleExists.where(
                    cb.equal(matched.get("id"), user.get("id")),
                    cb.equal(roles.get("name"), role)
            );
            all.add(cb.exists(roleExists));
        }
        return cb.and(all.toArray(Predicate[]::new));
    }

    static String searchPattern(String input) {
        if (input == null) {
            return null;
        }

        // Validate the ORIGINAL value. String.trim() removes C0 controls,
        // including NUL; validating only the trimmed value would accept them.
        validateRawSearchInput(input);
        if (input.isBlank()) {
            return null;
        }

        String normalized = input.trim().toLowerCase(java.util.Locale.ROOT);
        // Case folding can expand Unicode characters, so check the final length.
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("Некорректная строка поиска пользователей");
        }

        // Escape SQL LIKE wildcards, including the escape char itself.
        return "%" + normalized.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private static void validateRawSearchInput(String input) {
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);

            if (Character.isISOControl(current)) {
                throw new BadRequestException("Некорректная строка поиска пользователей");
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= input.length()
                        || !Character.isLowSurrogate(input.charAt(index + 1))) {
                    throw new BadRequestException("Некорректная строка поиска пользователей");
                }
                index++; // Consume a complete UTF-16 surrogate pair.
            } else if (Character.isLowSurrogate(current)) {
                throw new BadRequestException("Некорректная строка поиска пользователей");
            }
        }
    }
}
