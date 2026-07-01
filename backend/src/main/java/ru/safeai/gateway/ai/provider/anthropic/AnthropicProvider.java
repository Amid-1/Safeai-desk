package ru.safeai.gateway.ai.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiProviderSupport;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "anthropic"
)
public class AnthropicProvider implements AiProvider {

    private static final String PROVIDER_NAME = "anthropic";

    private final AnthropicProperties properties;
    private final RestClient client;
    private final ModelPricingService pricingService;
    private final AiProviderRetryExecutor retryExecutor;

    public AnthropicProvider(
            AnthropicProperties properties,
            ModelPricingService pricingService,
            AiProviderRetryExecutor retryExecutor
    ) {
        properties.validate();

        this.properties = properties;
        this.pricingService = pricingService;
        this.retryExecutor = retryExecutor;
        this.client = AiRestClientFactory.create(
                properties.baseUrl(),
                properties.connectTimeout(),
                properties.readTimeout()
        );
    }

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        return retryExecutor.execute(
                PROVIDER_NAME,
                properties.model(),
                () -> doSendMessage(request)
        );
    }

    private AiChatResponse doSendMessage(AiChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("model", properties.model());
        payload.put("max_tokens", properties.maxTokens());
        payload.put(
                "messages",
                AiProviderSupport.buildMessages(
                        request,
                        this::normalizeRole,
                        Set.of("SYSTEM")
                )
        );

        String systemPrompt = AiProviderSupport.extractSystemPrompt(request);

        if (!systemPrompt.isBlank()) {
            payload.put("system", systemPrompt);
        }

        log.info(
                "Sending request to Anthropic: userId={}, organizationId={}, chatId={}, model={}, messageLength={}",
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

            String content = AiProviderSupport.requireValidContent(
                    PROVIDER_NAME,
                    properties.model(),
                    extractText(response),
                    properties.maxResponseChars()
            );

            BigDecimal costUsd = pricingService.calculateCostUsd(
                    properties.model(),
                    inputTokens,
                    outputTokens
            );

            log.info(
                    "Anthropic response received: userId={}, organizationId={}, chatId={}, model={}, durationMs={}, inputTokens={}, outputTokens={}, costUsd={}",
                    request.userId(),
                    request.organizationId(),
                    request.chatId(),
                    properties.model(),
                    durationMs,
                    inputTokens,
                    outputTokens,
                    costUsd
            );

            return new AiChatResponse(
                    content,
                    properties.model(),
                    inputTokens,
                    outputTokens,
                    costUsd
            );
        } catch (ResourceAccessException exception) {
            if (AiProviderSupport.isTimeout(exception)) {
                throw new AiProviderTimeoutException(
                        PROVIDER_NAME,
                        properties.model(),
                        "Anthropic provider timeout",
                        exception
                );
            }

            throw new AiProviderUnavailableException(
                    PROVIDER_NAME,
                    properties.model(),
                    "Anthropic provider unavailable",
                    exception
            );
        } catch (RestClientResponseException exception) {
            throw mapAnthropicHttpException(exception);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderException(
                    PROVIDER_NAME,
                    properties.model(),
                    null,
                    null,
                    false,
                    "Anthropic provider request failed",
                    exception
            );
        }
    }

    private AiProviderException mapAnthropicHttpException(
            RestClientResponseException exception
    ) {
        int status = exception.getStatusCode().value();
        String requestId = AiProviderSupport.extractProviderRequestId(exception);

        if (status == 429) {
            return new AiProviderRateLimitedException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    requestId,
                    "Anthropic API rate limited: status=" + status,
                    exception
            );
        }

        boolean retryable = status == 500
                || status == 502
                || status == 503
                || status == 504;

        return new AiProviderException(
                PROVIDER_NAME,
                properties.model(),
                status,
                requestId,
                retryable,
                "Anthropic API error: status=" + status,
                exception
        );
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

        if (!content.isArray()) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText())) {
                String text = item.path("text").asText("");

                if (!text.isBlank()) {
                    if (!result.isEmpty()) {
                        result.append("\n\n");
                    }

                    result.append(text);
                }
            }
        }

        return result.toString();
    }
}