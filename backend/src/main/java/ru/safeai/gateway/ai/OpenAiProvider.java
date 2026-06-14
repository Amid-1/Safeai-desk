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
@EnableConfigurationProperties(OpenAiProperties.class)
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "openai"
)
public class OpenAiProvider implements AiProvider {

    private final OpenAiProperties properties;

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        properties.validate();

        RestClient client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();

        Map<String, Object> payload = Map.of(
                "model", properties.model(),
                "input", AiProviderSupport.buildMessages(request, this::normalizeRole)
        );

        try {
            JsonNode response = client.post()
                    .uri("/responses")
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            String content = extractOutputText(response);

            if (content.isBlank()) {
                throw new AiProviderException("OpenAI provider returned empty response");
            }

            return new AiChatResponse(
                    content,
                    properties.model(),
                    AiProviderSupport.extractInputTokens(response),
                    AiProviderSupport.extractOutputTokens(response),
                    BigDecimal.ZERO
            );
        } catch (RestClientResponseException exception) {
            throw new AiProviderException(
                    "OpenAI API error: status=" + exception.getStatusCode(),
                    exception
            );
        } catch (RuntimeException exception) {
            throw new AiProviderException("OpenAI provider request failed", exception);
        }
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }

        String normalized = role.trim().toLowerCase();

        return switch (normalized) {
            case "assistant" -> "assistant";
            case "system" -> "system";
            case "developer" -> "developer";
            default -> "user";
        };
    }

    private String extractOutputText(JsonNode response) {
        if (response == null) {
            return "";
        }

        String directOutputText = response.path("output_text").asText(null);
        if (directOutputText != null && !directOutputText.isBlank()) {
            return directOutputText;
        }

        JsonNode output = response.path("output");

        if (output.isArray()) {
            for (JsonNode outputItem : output) {
                JsonNode content = outputItem.path("content");

                if (content.isArray()) {
                    for (JsonNode contentItem : content) {
                        String type = contentItem.path("type").asText();

                        if ("output_text".equals(type)) {
                            return contentItem.path("text").asText("");
                        }
                    }
                }
            }
        }

        return "";
    }
}