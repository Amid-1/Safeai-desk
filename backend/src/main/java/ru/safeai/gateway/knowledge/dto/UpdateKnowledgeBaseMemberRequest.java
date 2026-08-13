package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;

public record UpdateKnowledgeBaseMemberRequest(
        @NotNull
        KnowledgeBaseAccessLevel accessLevel,

        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
}
