package ru.safeai.gateway.knowledge.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record KnowledgeBaseMemberPageResponse(
        List<KnowledgeBaseMemberResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public KnowledgeBaseMemberPageResponse {
        content = List.copyOf(content);
    }

    public static KnowledgeBaseMemberPageResponse from(
            Page<KnowledgeBaseMemberResponse> source
    ) {
        return new KnowledgeBaseMemberPageResponse(
                source.getContent(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages()
        );
    }
}
