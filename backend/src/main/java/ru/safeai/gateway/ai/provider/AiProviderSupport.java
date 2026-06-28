package ru.safeai.gateway.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.exception.AiProviderException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AiProviderSupport {

    private static final List<String> PROVIDER_REQUEST_ID_HEADERS = List.of(
            "x-request-id",
            "request-id",
            "openai-request-id",
            "anthropic-request-id"
    );

    private AiProviderSupport() {
    }

    public static List<Map<String, String>> buildMessages(
            AiChatRequest request,
            Function<String, String> roleNormalizer
    ) {
        return buildMessages(request, roleNormalizer, Set.of());
    }

    public static List<Map<String, String>> buildMessages(
            AiChatRequest request,
            Function<String, String> roleNormalizer,
            Set<String> excludedSourceRoles
    ) {
        List<Map<String, String>> messages = new ArrayList<>();

        request.history()
                .stream()
                .filter(message -> message != null && !message.content().isBlank())
                .forEach(message -> {
                    String sourceRole = message.role() == null
                            ? ""
                            : message.role().trim().toUpperCase();

                    if (excludedSourceRoles.contains(sourceRole)) {
                        return;
                    }

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

    public static String extractSystemPrompt(AiChatRequest request) {
        return request.history()
                .stream()
                .filter(message -> message != null && !message.content().isBlank())
                .filter(message -> "SYSTEM".equalsIgnoreCase(message.role()))
                .map(AiMessage::content)
                .collect(Collectors.joining("\n\n"));
    }

    public static Integer extractInputTokens(JsonNode response) {
        if (response == null) {
            return 0;
        }

        JsonNode usage = response.path("usage");

        if (usage.has("input_tokens")) {
            return usage.path("input_tokens").asInt(0);
        }

        if (usage.has("prompt_tokens")) {
            return usage.path("prompt_tokens").asInt(0);
        }

        return 0;
    }

    public static Integer extractOutputTokens(JsonNode response) {
        if (response == null) {
            return 0;
        }

        JsonNode usage = response.path("usage");

        if (usage.has("output_tokens")) {
            return usage.path("output_tokens").asInt(0);
        }

        if (usage.has("completion_tokens")) {
            return usage.path("completion_tokens").asInt(0);
        }

        return 0;
    }

    public static boolean isTimeout(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    public static String extractProviderRequestId(RestClientResponseException exception) {
        HttpHeaders headers = exception.getResponseHeaders();

        if (headers == null) {
            return null;
        }

        for (String headerName : PROVIDER_REQUEST_ID_HEADERS) {
            String value = headers.getFirst(headerName);

            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    public static String requireValidContent(
            String provider,
            String model,
            String content,
            int maxChars
    ) {
        if (content == null || content.isBlank()) {
            throw new AiProviderException(
                    provider,
                    model,
                    null,
                    null,
                    false,
                    provider + " provider returned empty response"
            );
        }

        if (content.length() > maxChars) {
            throw new AiProviderException(
                    provider,
                    model,
                    null,
                    null,
                    false,
                    provider + " provider response is too large"
            );
        }

        return content;
    }
}