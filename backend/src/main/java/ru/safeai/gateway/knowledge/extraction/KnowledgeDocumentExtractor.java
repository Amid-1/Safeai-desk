package ru.safeai.gateway.knowledge.extraction;

public interface KnowledgeDocumentExtractor {

    boolean supports(String mediaType);

    ExtractedDocument extract(byte[] content);
}
