package ru.safeai.gateway.knowledge.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record KnowledgeDocumentVersionPageResponse(
        List<KnowledgeDocumentVersionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public KnowledgeDocumentVersionPageResponse {
        content = List.copyOf(content);
    }

    public static KnowledgeDocumentVersionPageResponse from(
            Page<KnowledgeDocumentVersionResponse> page
    ) {
        return new KnowledgeDocumentVersionPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
