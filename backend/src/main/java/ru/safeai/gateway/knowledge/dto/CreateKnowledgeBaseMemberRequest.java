package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;

import java.util.UUID;

public record CreateKnowledgeBaseMemberRequest(
        @NotNull
        UUID userId,

        @NotNull
        KnowledgeBaseAccessLevel accessLevel
) {
}
