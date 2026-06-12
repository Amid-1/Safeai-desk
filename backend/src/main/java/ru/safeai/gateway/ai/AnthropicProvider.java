package ru.safeai.gateway.ai;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AnthropicProperties.class)
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "anthropic"
)
public class AnthropicProvider implements AiProvider {

    private final AnthropicProperties properties;

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        properties.validate();

        RestClient client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-api-key", properties.apiKey())
                .defaultHeader("anthropic-version", properties.version())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();

        Map<String, Object> payload = Map.of(
                "model", properties.model(),
                "max_tokens", properties.maxTokens(),
                "messages", AiProviderSupport.buildMessages(request, this::normalizeRole)
        );

        try {
            JsonNode response = client.post()
                    .uri("/messages")
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            return new AiChatResponse(
                    extractText(response),
                    properties.model(),
                    AiProviderSupport.extractInputTokens(response),
                    AiProviderSupport.extractOutputTokens(response),
                    BigDecimal.ZERO
            );
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "Anthropic API error: status=" + exception.getStatusCode()
                            + ", body=" + exception.getResponseBodyAsString(),
                    exception
            );
        }
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }

        String normalized = role.trim().toLowerCase();

        return "assistant".equals(normalized) ? "assistant" : "user";
    }

    private String extractText(JsonNode response) {
        if (response == null) {
            return "";
        }

        JsonNode content = response.path("content");

        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    return item.path("text").asText("");
                }
            }
        }

        return "";
    }
}