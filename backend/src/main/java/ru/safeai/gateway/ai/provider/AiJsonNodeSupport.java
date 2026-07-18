package ru.safeai.gateway.ai.provider;

import tools.jackson.databind.JsonNode;

public final class AiJsonNodeSupport {

    private AiJsonNodeSupport() {
    }

    public static String textOrNull(
            JsonNode node
    ) {
        if (node == null || node.isNull()) {
            return null;
        }

        String value = node.asString();

        if (value == null || value.isBlank()) {
            return null;
        }

        return value;
    }
}
