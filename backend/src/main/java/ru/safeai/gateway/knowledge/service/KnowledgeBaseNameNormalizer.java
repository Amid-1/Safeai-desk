package ru.safeai.gateway.knowledge.service;

public final class KnowledgeBaseNameNormalizer {

    private static final String FIELD_DISPLAY_NAME =
            "Название базы знаний";

    private KnowledgeBaseNameNormalizer() {
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
