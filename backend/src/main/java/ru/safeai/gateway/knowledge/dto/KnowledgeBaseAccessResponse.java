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
            KnowledgeAccessService.Access access
    ) {
        Objects.requireNonNull(access, "access не должен быть null");

        boolean administrator = access.administrator();

        return new KnowledgeBaseAccessResponse(
                access.knowledgeBase().getId(),
                access.level(),
                administrator,
                administrator || access.atLeast(KnowledgeBaseAccessLevel.EDITOR),
                administrator,
                administrator
        );
    }
}