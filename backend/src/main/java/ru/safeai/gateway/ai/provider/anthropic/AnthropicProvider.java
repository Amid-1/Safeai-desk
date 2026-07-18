package ru.safeai.gateway.ai.provider.anthropic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderOverloadedException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiProviderSupport;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.ai.provider.AiResponseMetadataService;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static ru.safeai.gateway.ai.provider.AiJsonNodeSupport.textOrNull;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.fromResourceAccess;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.parsingFailure;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.unknownFailure;

@Slf4j
@Service
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "anthropic"
)
public class AnthropicProvider implements AiProvider {

    private static final String PROVIDER_NAME = "anthropic";
    private static final String PROVIDER_DISPLAY_NAME = "Anthropic";

    private final AnthropicProperties properties;
    private final RestClient client;
    private final AiProviderRetryExecutor retryExecutor;
    private final Clock clock;
    private final AiResponseMetadataService responseMetadataService;

    public AnthropicProvider(
            AnthropicProperties properties,
            AiResponseMetadataService responseMetadataService,
            AiProviderRetryExecutor retryExecutor,
            Clock clock
    ) {
        this.properties = properties;
        this.responseMetadataService = responseMetadataService;
        this.retryExecutor = retryExecutor;
        this.clock = clock;
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
        Map<String, Object> payload = createPayload(request);
        long startedAt = System.nanoTime();

        try {
            JsonNode response = client.post()
                    .uri("/messages")
                    .header("x-api-key", properties.apiKey())
                    .header(
                            "anthropic-version",
                            properties.version()
                    )
                    .header(
                            HttpHeaders.CONTENT_TYPE,
                            "application/json"
                    )
                    .header(
                            "X-Client-Request-Id",
                            request.providerOperationId().toString()
                    )
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            return createResponse(
                    response,
                    startedAt
            );
        } catch (ResourceAccessException exception) {
            throw fromResourceAccess(
                    PROVIDER_NAME,
                    PROVIDER_DISPLAY_NAME,
                    properties.model(),
                    exception
            );
        } catch (RestClientResponseException exception) {
            throw mapHttpException(exception);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unknownFailure(
                    PROVIDER_NAME,
                    PROVIDER_DISPLAY_NAME,
                    properties.model(),
                    exception
            );
        }
    }

    private Map<String, Object> createPayload(
            AiChatRequest request
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("model", properties.model());
        payload.put("max_tokens", properties.maxTokens());
        payload.put(
                "messages",
                AiProviderSupport.buildMessages(
                        request,
                        this::normalizeRole,
                        Set.of(
                                AiMessageRole.SYSTEM,
                                AiMessageRole.DEVELOPER
                        )
                )
        );

        String systemPrompt =
                AiProviderSupport.extractSystemPrompt(request);

        if (!systemPrompt.isBlank()) {
            payload.put("system", systemPrompt);
        }

        return payload;
    }

    private AiChatResponse createResponse(
            JsonNode response,
            long startedAt
    ) {
        ParsedAnthropicResponse parsed =
                parseResponse(response);

        AiResponseMetadataService.AiResponseMetadata metadata =
                responseMetadataService.extract(
                        response,
                        properties.model()
                );

        long durationMs =
                (System.nanoTime() - startedAt) / 1_000_000;

        log.debug(
                "Anthropic response: model={}, outcome={}, stopReason={}, "
                        + "durationMs={}, inputTokens={}, outputTokens={}, "
                        + "pricingStatus={}",
                properties.model(),
                parsed.status(),
                parsed.stopReason(),
                durationMs,
                metadata.inputTokens(),
                metadata.outputTokens(),
                metadata.pricing().status()
        );

        return AiChatResponse.fromProvider(
                parsed.content(),
                properties.model(),
                parsed.messageId(),
                parsed.status(),
                parsed.stopReason(),
                metadata.inputTokens(),
                metadata.outputTokens(),
                metadata.pricing()
        );
    }

    private AiProviderException mapHttpException(
            RestClientResponseException exception
    ) {
        int status = exception.getStatusCode().value();

        String requestId =
                AiProviderSupport.extractProviderRequestId(
                        exception
                );

        Duration retryAfter =
                AiProviderSupport.extractRetryAfter(
                        exception,
                        clock
                );

        if (status == 429) {
            return new AiProviderRateLimitedException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    requestId,
                    retryAfter,
                    retryAfter != null,
                    "Anthropic API rate limited",
                    exception
            );
        }

        if (status == 529) {
            return new AiProviderOverloadedException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    requestId,
                    retryAfter,
                    "Anthropic API overloaded",
                    exception
            );
        }

        AiProviderErrorType type = switch (status) {
            case 401, 403 ->
                    AiProviderErrorType.AUTHENTICATION;

            case 400, 404, 413 ->
                    AiProviderErrorType.INVALID_REQUEST;

            default -> status >= 500
                    ? AiProviderErrorType.SERVER_ERROR
                    : AiProviderErrorType.UNKNOWN;
        };

        return new AiProviderException(
                PROVIDER_NAME,
                properties.model(),
                status,
                requestId,
                type,
                false,
                status >= 500,
                retryAfter,
                "Anthropic API error: status=" + status,
                exception
        );
    }

    private ParsedAnthropicResponse parseResponse(
            JsonNode response
    ) {
        if (response == null) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    properties.model(),
                    "Anthropic returned null response"
            );
        }

        String messageId =
                textOrNull(response.get("id"));

        String stopReason =
                textOrNull(response.get("stop_reason"));

        JsonNode content = response.get("content");

        if (content == null || !content.isArray()) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    properties.model(),
                    "Anthropic returned invalid content"
            );
        }

        StringBuilder text = new StringBuilder();

        for (JsonNode item : content) {
            if (!"text".equals(
                    textOrNull(item.get("type"))
            )) {
                continue;
            }

            String value =
                    textOrNull(item.get("text"));

            if (value == null) {
                continue;
            }

            if (!text.isEmpty()) {
                text.append("\n\n");
            }

            text.append(value);
        }

        String validContent =
                AiProviderSupport.requireValidContent(
                        PROVIDER_NAME,
                        properties.model(),
                        text.toString(),
                        properties.maxResponseChars()
                );

        AiResponseStatus status =
                "max_tokens".equals(stopReason)
                        ? AiResponseStatus.INCOMPLETE
                        : AiResponseStatus.COMPLETED;

        return new ParsedAnthropicResponse(
                validContent,
                messageId,
                status,
                stopReason
        );
    }

    private String normalizeRole(AiMessageRole role) {
        return role == AiMessageRole.ASSISTANT
                ? "assistant"
                : "user";
    }

    private record ParsedAnthropicResponse(
            String content,
            String messageId,
            AiResponseStatus status,
            String stopReason
    ) {
    }
}