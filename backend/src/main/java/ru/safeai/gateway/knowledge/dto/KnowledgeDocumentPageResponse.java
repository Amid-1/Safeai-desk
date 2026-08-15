package ru.safeai.gateway.knowledge.dto;

import java.util.List;

public record KnowledgeDocumentPageResponse(
        List<KnowledgeDocumentResponse>
        content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
