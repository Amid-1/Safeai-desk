package ru.safeai.gateway.ai.provider.openai;

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
import java.util.Map;

import static ru.safeai.gateway.ai.provider.AiJsonNodeSupport.textOrNull;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.fromResourceAccess;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.parsingFailure;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.unknownFailure;


@Slf4j
@Service
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "openai"
)
public class OpenAiProvider implements AiProvider {

    private static final String PROVIDER_NAME = "openai";
    private static final String PROVIDER_DISPLAY_NAME = "OpenAI";

    private final OpenAiProperties properties;
    private final RestClient client;
    private final AiResponseMetadataService responseMetadataService;
    private final AiProviderRetryExecutor retryExecutor;
    private final Clock clock;

    public OpenAiProvider(
            OpenAiProperties properties,
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
        Map<String, Object> payload = Map.of(
                "model",
                properties.model(),
                "input",
                AiProviderSupport.buildMessages(
                        request,
                        this::normalizeRole
                ),
                "max_output_tokens",
                properties.maxOutputTokens(),
                "store",
                properties.effectiveStore()
        );

        long startedAt = System.nanoTime();

        try {
            JsonNode response = client.post()
                    .uri("/responses")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + properties.apiKey()
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

    private AiChatResponse createResponse(
            JsonNode response,
            long startedAt
    ) {
        ParsedOpenAiResponse parsed =
                parseResponse(response);

        AiResponseMetadataService.AiResponseMetadata metadata =
                responseMetadataService.extract(
                        response,
                        properties.model()
                );

        long durationMs =
                (System.nanoTime() - startedAt) / 1_000_000;

        log.debug(
                "OpenAI response: model={}, outcome={}, durationMs={}, "
                        + "inputTokens={}, outputTokens={}, pricingStatus={}",
                properties.model(),
                parsed.status(),
                durationMs,
                metadata.inputTokens(),
                metadata.outputTokens(),
                metadata.pricing().status()
        );

        return AiChatResponse.fromProvider(
                parsed.content(),
                properties.model(),
                parsed.providerMessageId(),
                parsed.status(),
                parsed.finishReason(),
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
                    "OpenAI API rate limited",
                    exception
            );
        }

        AiProviderErrorType type = switch (status) {
            case 401, 403 ->
                    AiProviderErrorType.AUTHENTICATION;

            case 400, 404, 409, 422 ->
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
                "OpenAI API error: status=" + status,
                exception
        );
    }

    private ParsedOpenAiResponse parseResponse(
            JsonNode response
    ) {
        if (response == null) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    properties.model(),
                    "OpenAI returned null response"
            );
        }

        String providerMessageId =
                textOrNull(response.get("id"));

        String statusValue =
                textOrNull(response.get("status"));

        if ("failed".equals(statusValue)
                || "cancelled".equals(statusValue)) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    properties.model(),
                    "OpenAI response status=" + statusValue
            );
        }

        String normalText =
                extractOutputText(
                        response,
                        "output_text",
                        "text"
                );

        String refusalText =
                extractOutputText(
                        response,
                        "refusal",
                        "refusal"
                );

        AiResponseStatus status;
        String content;
        String finishReason = statusValue;

        if (normalText != null) {
            content = normalText;
            status = "incomplete".equals(statusValue)
                    ? AiResponseStatus.INCOMPLETE
                    : AiResponseStatus.COMPLETED;
        } else if (refusalText != null) {
            content = refusalText;
            status = AiResponseStatus.REFUSED;
            finishReason = "refusal";
        } else {
            throw parsingFailure(
                    PROVIDER_NAME,
                    properties.model(),
                    "OpenAI returned no output text or refusal"
            );
        }

        return new ParsedOpenAiResponse(
                content,
                providerMessageId,
                status,
                finishReason
        );
    }

    private String extractOutputText(
            JsonNode response,
            String acceptedType,
            String fieldName
    ) {
        if ("output_text".equals(acceptedType)) {
            String direct =
                    textOrNull(response.get("output_text"));

            if (direct != null) {
                return direct;
            }
        }

        JsonNode output = response.get("output");

        if (output == null || !output.isArray()) {
            return null;
        }

        StringBuilder result = new StringBuilder();

        for (JsonNode outputItem : output) {
            JsonNode content = outputItem.get("content");

            if (content == null || !content.isArray()) {
                continue;
            }

            for (JsonNode contentItem : content) {
                String type =
                        textOrNull(contentItem.get("type"));

                if (!acceptedType.equals(type)) {
                    continue;
                }

                String value =
                        textOrNull(contentItem.get(fieldName));

                if (value == null) {
                    continue;
                }

                if (!result.isEmpty()) {
                    result.append("\n\n");
                }

                result.append(value);
            }
        }

        return result.isEmpty()
                ? null
                : result.toString();
    }

    private String normalizeRole(AiMessageRole role) {
        return role.providerValue();
    }

    private record ParsedOpenAiResponse(
            String content,
            String providerMessageId,
            AiResponseStatus status,
            String finishReason
    ) {
    }
}