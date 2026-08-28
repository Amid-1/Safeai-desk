package ru.safeai.gateway.ai.provider.openai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderBillingException;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderOverloadedException;
import ru.safeai.gateway.ai.exception.AiProviderQuotaExceededException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.provider.AiContextWindowService;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiProviderSupport;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.ai.provider.AiResponseMetadataService;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
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
        havingValue = "openai"
)
public class OpenAiProvider implements AiProvider {

    private static final String PROVIDER_NAME = "openai";
    private static final String PROVIDER_DISPLAY_NAME = "OpenAI";

    private static final Set<String> QUOTA_CODES = Set.of(
            "insufficient_quota",
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded"
    );

    private static final Set<String> BILLING_CODES = Set.of(
            "billing_error",
            "billing_not_active"
    );

    private final OpenAiProperties properties;
    private final RestClient client;
    private final AiResponseMetadataService responseMetadataService;
    private final AiProviderRetryExecutor retryExecutor;
    private final AiContextWindowService contextWindowService;
    private final Clock clock;

    @Autowired
    public OpenAiProvider(
            OpenAiProperties properties,
            AiResponseMetadataService responseMetadataService,
            AiProviderRetryExecutor retryExecutor,
            AiContextWindowService contextWindowService,
            Clock clock
    ) {
        this(
                properties,
                responseMetadataService,
                retryExecutor,
                contextWindowService,
                clock,
                AiRestClientFactory.create(
                        properties.baseUrl(),
                        properties.connectTimeout(),
                        properties.readTimeout(),
                        properties.maxResponseBodyBytes()
                )
        );
    }

    OpenAiProvider(
            OpenAiProperties properties,
            AiResponseMetadataService responseMetadataService,
            AiProviderRetryExecutor retryExecutor,
            AiContextWindowService contextWindowService,
            Clock clock,
            RestClient client
    ) {
        this.properties = properties;
        this.responseMetadataService = responseMetadataService;
        this.retryExecutor = retryExecutor;
        this.contextWindowService = contextWindowService;
        this.clock = clock;
        this.client = client;
    }

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        AiChatRequest prepared = contextWindowService.prepare(
                request,
                properties.maxInputTokens(),
                properties.maxOutputTokens()
        );

        return retryExecutor.execute(
                PROVIDER_NAME,
                properties.model(),
                prepared.providerOperationId(),
                attemptTimeout(),
                attempt -> doSendMessage(prepared, attempt)
        );
    }

    private AiChatResponse doSendMessage(
            AiChatRequest request,
            AiProviderAttemptContext attempt
    ) {
        Map<String, Object> payload = Map.of(
                "model",
                properties.model(),
                "input",
                AiProviderSupport.buildOpenAiInput(request),
                "max_output_tokens",
                properties.maxOutputTokens(),
                "store",
                properties.effectiveStore()
        );

        long startedAt = System.nanoTime();

        try {
            ResponseEntity<JsonNode> responseEntity = client.post()
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
                            attempt.attemptId().toString()
                    )
                    .body(payload)
                    .retrieve()
                    .toEntity(JsonNode.class);

            String providerRequestId =
                    AiProviderSupport.extractProviderRequestId(
                            responseEntity.getHeaders()
                    );

            return createResponse(
                    responseEntity.getBody(),
                    providerRequestId,
                    attempt,
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
            String providerRequestId,
            AiProviderAttemptContext attempt,
            long startedAt
    ) {
        ParsedOpenAiResponse parsed = parseResponse(response);

        AiResponseMetadataService.AiResponseMetadata metadata =
                responseMetadataService.extract(
                        response,
                        parsed.actualModel()
                );

        long durationMs =
                (System.nanoTime() - startedAt) / 1_000_000;

        log.debug(
                "OpenAI response: requestedModel={}, actualModel={}, "
                        + "operationId={}, attemptId={}, providerRequestId={}, "
                        + "outcome={}, finishReason={}, durationMs={}, "
                        + "inputTokens={}, cachedInputTokens={}, "
                        + "cacheWriteInputTokens={}, outputTokens={}, "
                        + "pricingStatus={}",
                properties.model(),
                parsed.actualModel(),
                attempt.operationId(),
                attempt.attemptId(),
                providerRequestId,
                parsed.status(),
                parsed.finishReason(),
                durationMs,
                metadata.inputTokens(),
                metadata.cachedInputTokens(),
                metadata.cacheWriteInputTokens(),
                metadata.outputTokens(),
                metadata.pricing().status()
        );

        return AiChatResponse.fromProvider(
                parsed.content(),
                properties.model(),
                parsed.actualModel(),
                parsed.providerMessageId(),
                providerRequestId,
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
                AiProviderSupport.extractProviderRequestId(exception);

        Duration retryAfter =
                AiProviderSupport.extractRetryAfter(
                        exception,
                        clock
                );

        AiProviderSupport.ProviderErrorDetails error =
                AiProviderSupport.extractProviderError(exception);

        String errorCode = error.code() != null
                ? error.code()
                : error.type();

        if (status == 429) {
            if (errorCode != null
                    && QUOTA_CODES.contains(errorCode)) {
                return new AiProviderQuotaExceededException(
                        PROVIDER_NAME,
                        properties.model(),
                        status,
                        requestId,
                        errorCode,
                        "OpenAI quota or spend limit exhausted",
                        exception
                );
            }

            if (errorCode != null
                    && BILLING_CODES.contains(errorCode)) {
                return new AiProviderBillingException(
                        PROVIDER_NAME,
                        properties.model(),
                        status,
                        requestId,
                        errorCode,
                        "OpenAI billing error",
                        exception
                );
            }

            return new AiProviderRateLimitedException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    requestId,
                    errorCode,
                    retryAfter,
                    true,
                    "OpenAI API rate limited",
                    exception
            );
        }

        if (status == 503) {
            return new AiProviderOverloadedException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    requestId,
                    retryAfter,
                    "OpenAI API overloaded",
                    exception
            );
        }

        AiProviderErrorType type = switch (status) {
            case 401 -> AiProviderErrorType.AUTHENTICATION;

            case 403 -> AiProviderErrorType.PERMISSION_DENIED;

            case 400, 404, 409, 413, 422 -> AiProviderErrorType.INVALID_REQUEST;

            case 408, 504 -> AiProviderErrorType.TIMEOUT;

            default -> status >= 500
                    ? AiProviderErrorType.SERVER_ERROR
                    : AiProviderErrorType.UNKNOWN;
        };

        boolean ambiguous =
                status == 408
                        || status >= 500;

        return new AiProviderException(
                PROVIDER_NAME,
                properties.model(),
                status,
                requestId,
                errorCode,
                type,
                false,
                ambiguous,
                retryAfter,
                "OpenAI API error: status=" + status,
                exception
        );
    }

    private ParsedOpenAiResponse parseResponse(JsonNode response) {
        if (response == null) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    properties.model(),
                    "OpenAI returned null response"
            );
        }

        String providerMessageId =
                textOrNull(response.get("id"));

        String actualModel = AiProviderSupport.resolvedModel(
                response,
                properties.model()
        );

        String statusValue =
                textOrNull(response.get("status"));

        if (statusValue == null) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "OpenAI returned no response status"
            );
        }

        switch (statusValue) {
            case "failed", "cancelled" -> throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "OpenAI response status=" + statusValue
            );
            case "queued", "in_progress" -> throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "Unexpected async OpenAI response status="
                            + statusValue
            );
            case "completed", "incomplete" -> {
                // Разбор результата ниже.
            }
            default -> throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "Unknown OpenAI response status=" + statusValue
            );
        }

        String normalText = extractOutputText(
                response,
                "output_text",
                "text",
                actualModel
        );

        String refusalText = extractOutputText(
                response,
                "refusal",
                "refusal",
                actualModel
        );

        AiResponseStatus status;
        String content;
        String finishReason;

        if (refusalText != null) {
            content = refusalText;
            status = AiResponseStatus.REFUSED;
            finishReason = "refusal";
        } else if (normalText != null) {
            content = normalText;

            if ("incomplete".equals(statusValue)) {
                status = AiResponseStatus.INCOMPLETE;
                finishReason = incompleteReason(response);
            } else {
                status = AiResponseStatus.COMPLETED;
                finishReason = "completed";
            }
        } else {
            throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "OpenAI returned no output text or refusal"
            );
        }

        String validContent =
                AiProviderSupport.requireValidContent(
                        PROVIDER_NAME,
                        actualModel,
                        content,
                        properties.maxResponseChars()
                );

        return new ParsedOpenAiResponse(
                validContent,
                providerMessageId,
                status,
                finishReason,
                actualModel
        );
    }

    private String extractOutputText(
            JsonNode response,
            String acceptedType,
            String fieldName,
            String actualModel
    ) {
        if ("output_text".equals(acceptedType)) {
            String direct =
                    textOrNull(response.get("output_text"));

            if (direct != null) {
                return AiProviderSupport.requireValidContent(
                        PROVIDER_NAME,
                        actualModel,
                        direct,
                        properties.maxResponseChars()
                );
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

                AiProviderSupport.appendBoundedText(
                        result,
                        value,
                        properties.maxResponseChars(),
                        PROVIDER_NAME,
                        actualModel
                );
            }
        }

        return result.isEmpty()
                ? null
                : result.toString();
    }

    private String incompleteReason(JsonNode response) {
        JsonNode details = response.get("incomplete_details");

        if (details == null || !details.isObject()) {
            return "incomplete";
        }

        String reason = textOrNull(details.get("reason"));
        return reason == null ? "incomplete" : reason;
    }

    private Duration attemptTimeout() {
        try {
            return properties.connectTimeout()
                    .plus(properties.readTimeout());
        } catch (ArithmeticException exception) {
            return properties.readTimeout();
        }
    }

    private record ParsedOpenAiResponse(
            String content,
            String providerMessageId,
            AiResponseStatus status,
            String finishReason,
            String actualModel
    ) {
    }
}
