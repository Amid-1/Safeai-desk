package ru.safeai.gateway.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class AiProviderSupport {

    private AiProviderSupport() {
    }

    static List<Map<String, String>> buildMessages(
            AiChatRequest request,
            Function<String, String> roleNormalizer
    ) {
        List<Map<String, String>> messages = new ArrayList<>();

        request.history()
                .stream()
                .filter(message -> message != null && !message.content().isBlank())
                .forEach(message -> {
                    String role = roleNormalizer.apply(message.role());

                    if (role == null || role.isBlank()) {
                        role = "user";
                    }

                    messages.add(Map.of(
                            "role", role,
                            "content", message.content()
                    ));
                });

        messages.add(Map.of(
                "role", "user",
                "content", request.userMessage()
        ));

        return messages;
    }

    static Integer extractInputTokens(JsonNode response) {
        if (response == null) {
            return 0;
        }

        return response.path("usage")
                .path("input_tokens")
                .asInt(0);
    }

    static Integer extractOutputTokens(JsonNode response) {
        if (response == null) {
            return 0;
        }

        return response.path("usage")
                .path("output_tokens")
                .asInt(0);
    }
}