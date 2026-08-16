package ru.safeai.gateway.knowledge.service;

/**
 * Нормализует пользовательское название документа.
 */
public final class KnowledgeDocumentNameNormalizer {

    private static final String FIELD_DISPLAY_NAME =
            "Название документа";

    private KnowledgeDocumentNameNormalizer() {
    }

    public static String normalize(
            String value
    ) {
        return KnowledgeNameNormalizerSupport.normalize(
                value,
                FIELD_DISPLAY_NAME
        );
    }
}