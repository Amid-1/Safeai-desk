package ru.safeai.gateway.knowledge.dto;

import java.util.UUID;

public record KnowledgeMemberCandidateResponse(
        UUID userId,
        String email,
        String fullName
) {
}
