package ru.safeai.gateway.knowledge.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record KnowledgeBasePageResponse(
        List<KnowledgeBaseResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public KnowledgeBasePageResponse {
        content = List.copyOf(content);
    }

    public static KnowledgeBasePageResponse from(
            Page<KnowledgeBaseResponse> source
    ) {
        return new KnowledgeBasePageResponse(
                source.getContent(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages()
        );
    }
}
