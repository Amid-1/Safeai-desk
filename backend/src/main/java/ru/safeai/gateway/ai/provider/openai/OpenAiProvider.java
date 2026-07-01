package ru.safeai.gateway.ai.provider.openai;

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
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "openai"
)
public class OpenAiProvider implements AiProvider {

    private static final String PROVIDER_NAME = "openai";

    private final OpenAiProperties properties;
    private final RestClient client;
    private final ModelPricingService pricingService;
    private final AiProviderRetryExecutor retryExecutor;

    public OpenAiProvider(
            OpenAiProperties properties,
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
        Map<String, Object> payload = Map.of(
                "model", properties.model(),
                "input", AiProviderSupport.buildMessages(request, this::normalizeRole),
                "max_output_tokens", properties.maxOutputTokens(),
                "store", properties.effectiveStore()
        );

        log.info(
                "Sending request to OpenAI: userId={}, organizationId={}, chatId={}, model={}, messageLength={}",
                request.userId(),
                request.organizationId(),
                request.chatId(),
                properties.model(),
                request.userMessage().length()
        );

        long startedAt = System.nanoTime();

        try {
            JsonNode response = client.post()
                    .uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
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
                    extractOutputText(response),
                    properties.maxResponseChars()
            );

            BigDecimal costUsd = pricingService.calculateCostUsd(
                    properties.model(),
                    inputTokens,
                    outputTokens
            );

            log.info(
                    "OpenAI response received: userId={}, organizationId={}, chatId={}, model={}, durationMs={}, inputTokens={}, outputTokens={}, costUsd={}",
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
                        "OpenAI provider timeout",
                        exception
                );
            }

            throw new AiProviderUnavailableException(
                    PROVIDER_NAME,
                    properties.model(),
                    "OpenAI provider unavailable",
                    exception
            );
        } catch (RestClientResponseException exception) {
            throw mapOpenAiHttpException(exception);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderException(
                    PROVIDER_NAME,
                    properties.model(),
                    null,
                    null,
                    false,
                    "OpenAI provider request failed",
                    exception
            );
        }
    }

    private AiProviderException mapOpenAiHttpException(
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
                    "OpenAI API rate limited: status=" + status,
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
                "OpenAI API error: status=" + status,
                exception
        );
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

        if (!output.isArray()) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (JsonNode outputItem : output) {
            JsonNode content = outputItem.path("content");

            if (!content.isArray()) {
                continue;
            }

            for (JsonNode contentItem : content) {
                String type = contentItem.path("type").asText("");

                if ("output_text".equals(type)) {
                    appendIfNotBlank(
                            result,
                            contentItem.path("text").asText("")
                    );
                }

                if ("refusal".equals(type)) {
                    appendIfNotBlank(
                            result,
                            contentItem.path("refusal").asText("")
                    );
                }
            }
        }

        return result.toString();
    }

    private void appendIfNotBlank(StringBuilder result, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        if (!result.isEmpty()) {
            result.append("\n\n");
        }

        result.append(value);
    }
}