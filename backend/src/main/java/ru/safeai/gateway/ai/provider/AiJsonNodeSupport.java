package ru.safeai.gateway.ai.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class AiJsonNodeSupport {

    private static final JsonMapper JSON_MAPPER =
            JsonMapper.builder().build();

    private AiJsonNodeSupport() {
    }

    public static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        String value = node.asString();

        if (value == null || value.isBlank()) {
            return null;
        }

        return value;
    }

    public static JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return JSON_MAPPER.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }
}
