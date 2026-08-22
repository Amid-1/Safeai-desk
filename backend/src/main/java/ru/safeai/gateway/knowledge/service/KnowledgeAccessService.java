package ru.safeai.gateway.knowledge.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseMembershipEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseMembershipRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseRepository;

import java.util.Objects;
import java.util.UUID;

@Service
public class KnowledgeAccessService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseMembershipRepository memberships;

    public KnowledgeAccessService(
            KnowledgeBaseRepository bases,
            KnowledgeBaseMembershipRepository memberships
    ) {
        this.bases = bases;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public Access requireAccess(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user,
            KnowledgeBaseAccessLevel required
    ) {
        Objects.requireNonNull(
                knowledgeBaseId,
                "knowledgeBaseId не должен быть null"
        );
        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );
        Objects.requireNonNull(
                required,
                "required не должен быть null"
        );

        KnowledgeBaseEntity base = bases
                .findByIdAndOrganizationId(
                        knowledgeBaseId,
                        user.getOrganizationId()
                )
                .orElseThrow(this::notFound);

        if (isAdmin(user)) {
            return new Access(
                    base,
                    KnowledgeBaseAccessLevel.OWNER,
                    true
            );
        }

        if (!base.isEnabled()) {
            throw notFound();
        }

        KnowledgeBaseAccessLevel membershipLevel =
                memberships
                        .findByKnowledgeBaseIdAndOrganizationIdAndUserId(
                                knowledgeBaseId,
                                user.getOrganizationId(),
                                user.getId()
                        )
                        .map(
                                KnowledgeBaseMembershipEntity::getAccessLevel
                        )
                        .orElse(null);

        if (membershipLevel == null
                && base.getVisibility()
                == KnowledgeBaseVisibility.MEMBERS) {
            throw notFound();
        }

        KnowledgeBaseAccessLevel actual =
                membershipLevel != null
                        ? membershipLevel
                        : KnowledgeBaseAccessLevel.VIEWER;

        if (!atLeast(actual, required)) {
            throw new ForbiddenOperationException(
                    "Недостаточно прав для операции с базой знаний."
            );
        }

        return new Access(
                base,
                actual,
                false
        );
    }

    public static boolean atLeast(
            KnowledgeBaseAccessLevel actual,
            KnowledgeBaseAccessLevel required
    ) {
        return rank(actual) >= rank(required);
    }

    public static boolean isAdmin(
            SafeAiUserPrincipal user
    ) {
        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );

        return user.getAuthorities()
                .stream()
                .anyMatch(
                        authority -> ROLE_ADMIN.equals(
                                authority.getAuthority()
                        )
                );
    }

    private static int rank(
            KnowledgeBaseAccessLevel level
    ) {
        return switch (level) {
            case VIEWER -> 1;
            case EDITOR -> 2;
            case OWNER -> 3;
        };
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException(
                "База знаний не найдена."
        );
    }

    public record Access(
            KnowledgeBaseEntity knowledgeBase,
            KnowledgeBaseAccessLevel level,
            boolean administrator
    ) {
        public Access {
            Objects.requireNonNull(
                    knowledgeBase,
                    "knowledgeBase не должен быть null"
            );
            Objects.requireNonNull(
                    level,
                    "level не должен быть null"
            );
        }

        public boolean atLeast(
                KnowledgeBaseAccessLevel required
        ) {
            return KnowledgeAccessService.atLeast(
                    level,
                    required
            );
        }
    }
}
