package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.service.KnowledgeAccessService;

import java.util.Objects;
import java.util.UUID;

public record KnowledgeBaseAccessResponse(
        UUID knowledgeBaseId,
        KnowledgeBaseAccessLevel accessLevel,
        boolean administrator,
        boolean canEditDocuments,
        boolean canManageBase,
        boolean canManageMembers
) {

    public KnowledgeBaseAccessResponse {
        Objects.requireNonNull(
                knowledgeBaseId,
                "knowledgeBaseId не должен быть null"
        );
        Objects.requireNonNull(
                accessLevel,
                "accessLevel не должен быть null"
        );
    }

    public static KnowledgeBaseAccessResponse from(
            UUID knowledgeBaseId,
            KnowledgeAccessService.Access access
    ) {
        Objects.requireNonNull(
                access,
                "access не должен быть null"
        );

        boolean canEditDocuments =
                access.administrator()
                        || access.atLeast(
                        KnowledgeBaseAccessLevel.EDITOR
                );

        /*
         * Current controller contract intentionally keeps base/member
         * administration tenant-admin only. OWNER does not silently gain
         * permissions that backend endpoints do not grant.
         */
        boolean canManageBase =
                access.administrator();

        boolean canManageMembers =
                access.administrator();

        return new KnowledgeBaseAccessResponse(
                knowledgeBaseId,
                access.level(),
                access.administrator(),
                canEditDocuments,
                canManageBase,
                canManageMembers
        );
    }
}
