package ru.safeai.gateway.ai;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service
@EnableConfigurationProperties(AnthropicProperties.class)
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "anthropic"
)
public class AnthropicProvider implements AiProvider {

    private final AnthropicProperties properties;
    private final RestClient client;

    public AnthropicProvider(AnthropicProperties properties) {
        this.properties = properties;
        this.client = AiRestClientFactory.create(
                properties.baseUrl(),
                properties.connectTimeout(),
                properties.readTimeout()
        );
    }

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        properties.validate();

        Map<String, Object> payload = Map.of(
                "model", properties.model(),
                "max_tokens", properties.maxTokens(),
                "messages", AiProviderSupport.buildMessages(request, this::normalizeRole)
        );

        log.info(
                "Отправка запроса в Anthropic: userId={}, organizationId={}, chatId={}, model={}, messageLength={}",
                request.userId(),
                request.organizationId(),
                request.chatId(),
                properties.model(),
                request.userMessage().length()
        );

        long startedAt = System.nanoTime();

        try {
            JsonNode response = client.post()
                    .uri("/messages")
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            int inputTokens = AiProviderSupport.extractInputTokens(response);
            int outputTokens = AiProviderSupport.extractOutputTokens(response);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            log.info(
                    "Anthropic response received: userId={}, organizationId={}, chatId={}, model={}, durationMs={}, inputTokens={}, outputTokens={}",
                    request.userId(),
                    request.organizationId(),
                    request.chatId(),
                    properties.model(),
                    durationMs,
                    inputTokens,
                    outputTokens
            );

            String content = extractText(response);

            if (content.isBlank()) {
                throw new AiProviderException("Anthropic provider returned empty response");
            }

            return new AiChatResponse(
                    content,
                    properties.model(),
                    inputTokens,
                    outputTokens,
                    BigDecimal.ZERO
            );
        } catch (ResourceAccessException exception) {
            throw new AiProviderTimeoutException("Anthropic provider timeout", exception);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw new AiProviderRateLimitedException(
                        "Anthropic API rate limited: status=" + exception.getStatusCode(),
                        exception
                );
            }

            throw new AiProviderException(
                    "Anthropic API error: status=" + exception.getStatusCode(),
                    exception
            );
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderException("Anthropic provider request failed", exception);
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