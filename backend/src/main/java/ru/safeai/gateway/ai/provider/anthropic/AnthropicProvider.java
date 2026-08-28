package ru.safeai.gateway.ai.provider.anthropic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderBillingException;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderOverloadedException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.provider.AiContextWindowService;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiProviderSupport;
import ru.safeai.gateway.ai.provider.AiResponseMetadataService;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ru.safeai.gateway.ai.provider.AiJsonNodeSupport.textOrNull;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.fromResourceAccess;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.parsingFailure;
import static ru.safeai.gateway.ai.provider.AiProviderExceptionFactory.unknownFailure;

@Slf4j
@SuppressWarnings("DuplicatedCode")
public final class AnthropicProvider implements AiProvider {

    private static final String PROVIDER_NAME =
            "anthropic";

    private static final String PROVIDER_DISPLAY_NAME =
            "Anthropic";

    private static final String MESSAGES_PATH =
            "/messages";

    private static final String API_KEY_HEADER =
            "x-api-key";

    private static final String API_VERSION_HEADER =
            "anthropic-version";

    private static final String RESPONSE_TYPE_MESSAGE =
            "message";

    private static final String RESPONSE_ROLE_ASSISTANT =
            "assistant";

    private static final String CONTENT_TYPE_TEXT =
            "text";

    private static final String STOP_END_TURN =
            "end_turn";

    private static final String STOP_SEQUENCE =
            "stop_sequence";

    private static final String STOP_MAX_TOKENS =
            "max_tokens";

    private static final String STOP_CONTEXT_EXCEEDED =
            "model_context_window_exceeded";

    private static final String STOP_PAUSE_TURN =
            "pause_turn";

    private static final String STOP_REFUSAL =
            "refusal";

    private static final String STOP_TOOL_USE =
            "tool_use";

    private final AnthropicProperties properties;
    private final AiResponseMetadataService responseMetadataService;
    private final AiProviderRetryExecutor retryExecutor;
    private final AiContextWindowService contextWindowService;
    private final Clock clock;
    private final RestClient client;

    public AnthropicProvider(
            AnthropicProperties properties,
            AiResponseMetadataService responseMetadataService,
            AiProviderRetryExecutor retryExecutor,
            AiContextWindowService contextWindowService,
            Clock clock,
            RestClient client
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        this.responseMetadataService = Objects.requireNonNull(
                responseMetadataService,
                "responseMetadataService не должен быть null"
        );

        this.retryExecutor = Objects.requireNonNull(
                retryExecutor,
                "retryExecutor не должен быть null"
        );

        this.contextWindowService = Objects.requireNonNull(
                contextWindowService,
                "contextWindowService не должен быть null"
        );

        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );

        this.client = Objects.requireNonNull(
                client,
                "client не должен быть null"
        );
    }

    @Override
    public AiChatResponse sendMessage(
            AiChatRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        AiChatRequest preparedRequest =
                contextWindowService.prepare(
                        request,
                        properties.maxInputTokens(),
                        properties.maxTokens()
                );

        return retryExecutor.execute(
                PROVIDER_NAME,
                properties.model(),
                preparedRequest.providerOperationId(),
                calculateAttemptTimeout(),
                attempt -> sendAttempt(
                        preparedRequest,
                        attempt
                )
        );
    }

    private AiChatResponse sendAttempt(
            AiChatRequest request,
            AiProviderAttemptContext attempt
    ) {
        Map<String, Object> payload =
                createPayload(request);

        long startedAtNanos =
                System.nanoTime();

        try {
            ResponseEntity<JsonNode> responseEntity =
                    client.post()
                            .uri(MESSAGES_PATH)
                            .header(
                                    API_KEY_HEADER,
                                    properties.apiKey()
                            )
                            .header(
                                    API_VERSION_HEADER,
                                    properties.version()
                            )
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .accept(
                                    MediaType.APPLICATION_JSON
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
                    startedAtNanos
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
        Map<String, Object> payload =
                new LinkedHashMap<>();

        payload.put(
                "model",
                properties.model()
        );

        payload.put(
                "max_tokens",
                properties.maxTokens()
        );

        payload.put(
                "messages",
                AiProviderSupport.buildAnthropicMessages(
                        request
                )
        );

        List<Map<String, String>> systemBlocks =
                AiProviderSupport.buildAnthropicSystem(
                        request
                );

        if (!systemBlocks.isEmpty()) {
            payload.put(
                    "system",
                    systemBlocks
            );
        }

        return Map.copyOf(payload);
    }

    private AiChatResponse createResponse(
            JsonNode response,
            String providerRequestId,
            AiProviderAttemptContext attempt,
            long startedAtNanos
    ) {
        ParsedAnthropicResponse parsed =
                parseResponse(response);

        AiResponseMetadataService.AiResponseMetadata metadata =
                responseMetadataService.extract(
                        response,
                        parsed.actualModel()
                );

        long durationMs =
                Duration.ofNanos(
                        System.nanoTime() - startedAtNanos
                ).toMillis();

        log.debug(
                "Anthropic response: requestedModel={}, actualModel={}, "
                        + "operationId={}, attemptId={}, "
                        + "providerMessageId={}, providerRequestId={}, "
                        + "responseStatus={}, stopReason={}, durationMs={}, "
                        + "inputTokens={}, cachedInputTokens={}, "
                        + "cacheWriteInputTokens={}, outputTokens={}, "
                        + "pricingStatus={}",
                properties.model(),
                parsed.actualModel(),
                attempt.operationId(),
                attempt.attemptId(),
                parsed.messageId(),
                providerRequestId,
                parsed.status(),
                parsed.stopReason(),
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
                parsed.messageId(),
                providerRequestId,
                parsed.status(),
                parsed.stopReason(),
                metadata.inputTokens(),
                metadata.outputTokens(),
                metadata.pricing()
        );
    }

    private ParsedAnthropicResponse parseResponse(
            JsonNode response
    ) {
        if (response == null || !response.isObject()) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    properties.model(),
                    "Anthropic returned null or non-object response"
            );
        }

        String actualModel =
                AiProviderSupport.resolvedModel(
                        response,
                        properties.model()
                );

        validateResponseEnvelope(
                response,
                actualModel
        );

        String messageId =
                textOrNull(
                        response.get("id")
                );

        String stopReason =
                textOrNull(
                        response.get("stop_reason")
                );

        AiResponseStatus status =
                mapStopReason(
                        stopReason,
                        actualModel
                );

        JsonNode content =
                response.get("content");

        if (content == null || !content.isArray()) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "Anthropic returned invalid content array"
            );
        }

        String textContent =
                extractTextContent(
                        content,
                        actualModel
                );

        String validContent =
                AiProviderSupport.requireValidContent(
                        PROVIDER_NAME,
                        actualModel,
                        textContent,
                        properties.maxResponseChars()
                );

        return new ParsedAnthropicResponse(
                validContent,
                messageId,
                status,
                stopReason,
                actualModel
        );
    }

    private void validateResponseEnvelope(
            JsonNode response,
            String actualModel
    ) {
        String responseType =
                textOrNull(
                        response.get("type")
                );

        if (responseType != null
                && !RESPONSE_TYPE_MESSAGE.equals(responseType)) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "Unexpected Anthropic response type: "
                            + responseType
            );
        }

        String responseRole =
                textOrNull(
                        response.get("role")
                );

        if (responseRole != null
                && !RESPONSE_ROLE_ASSISTANT.equals(responseRole)) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "Unexpected Anthropic response role: "
                            + responseRole
            );
        }
    }

    private String extractTextContent(
            JsonNode content,
            String actualModel
    ) {
        StringBuilder text =
                new StringBuilder();

        for (JsonNode block : content) {
            if (block == null || !block.isObject()) {
                throw parsingFailure(
                        PROVIDER_NAME,
                        actualModel,
                        "Anthropic returned invalid content block"
                );
            }

            String blockType =
                    textOrNull(
                            block.get("type")
                    );

            if (!CONTENT_TYPE_TEXT.equals(blockType)) {
                continue;
            }

            AiProviderSupport.appendBoundedText(
                    text,
                    textOrNull(block.get("text")),
                    properties.maxResponseChars(),
                    PROVIDER_NAME,
                    actualModel
            );
        }

        return text.toString();
    }

    private AiResponseStatus mapStopReason(
            String stopReason,
            String actualModel
    ) {
        if (stopReason == null) {
            throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "Anthropic returned no stop_reason"
            );
        }

        return switch (stopReason) {
            case STOP_END_TURN,
                 STOP_SEQUENCE -> AiResponseStatus.COMPLETED;

            case STOP_MAX_TOKENS,
                 STOP_CONTEXT_EXCEEDED,
                 STOP_PAUSE_TURN -> AiResponseStatus.INCOMPLETE;

            case STOP_REFUSAL -> AiResponseStatus.REFUSED;

            case STOP_TOOL_USE -> throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "Unexpected Anthropic tool_use response: "
                            + "tools are not enabled for this flow"
            );

            default -> throw parsingFailure(
                    PROVIDER_NAME,
                    actualModel,
                    "Unknown Anthropic stop_reason: "
                            + stopReason
            );
        };
    }

    private AiProviderException mapHttpException(
            RestClientResponseException exception
    ) {
        int status =
                exception.getStatusCode().value();

        String providerRequestId =
                AiProviderSupport.extractProviderRequestId(
                        exception
                );

        Duration retryAfter =
                AiProviderSupport.extractRetryAfter(
                        exception,
                        clock
                );

        AiProviderSupport.ProviderErrorDetails error =
                AiProviderSupport.extractProviderError(
                        exception
                );

        String errorCode =
                firstNonBlank(
                        error.type(),
                        error.code()
                );

        if (status == 402
                || matchesError(
                errorCode,
                "billing_error"
        )) {
            return new AiProviderBillingException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    providerRequestId,
                    errorCode,
                    "Anthropic billing error",
                    exception
            );
        }

        if (status == 429
                || matchesError(
                errorCode,
                "rate_limit_error"
        )) {
            return new AiProviderRateLimitedException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    providerRequestId,
                    errorCode,
                    retryAfter,
                    true,
                    "Anthropic API rate limited",
                    exception
            );
        }

        if (status == 529
                || matchesError(
                errorCode,
                "overloaded_error"
        )) {
            return new AiProviderOverloadedException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    providerRequestId,
                    retryAfter,
                    "Anthropic API overloaded",
                    exception
            );
        }

        if (status == 408
                || status == 504
                || matchesError(
                errorCode,
                "timeout_error"
        )) {
            return new AiProviderTimeoutException(
                    PROVIDER_NAME,
                    properties.model(),
                    status,
                    providerRequestId,
                    true,
                    "Anthropic API timed out after request submission",
                    exception
            );
        }

        AiProviderErrorType errorType =
                classifyHttpError(
                        status,
                        errorCode
                );

        boolean outcomeAmbiguous =
                status >= 500;

        return new AiProviderException(
                PROVIDER_NAME,
                properties.model(),
                status,
                providerRequestId,
                errorCode,
                errorType,
                false,
                outcomeAmbiguous,
                retryAfter,
                "Anthropic API error: status=" + status,
                exception
        );
    }

    private static AiProviderErrorType classifyHttpError(
            int status,
            String errorCode
    ) {
        if (status == 401
                || matchesError(
                errorCode,
                "authentication_error"
        )) {
            return AiProviderErrorType.AUTHENTICATION;
        }

        if (status == 403
                || matchesError(
                errorCode,
                "permission_error"
        )) {
            return AiProviderErrorType.PERMISSION_DENIED;
        }

        if (status == 400
                || status == 404
                || status == 409
                || status == 413
                || status == 422
                || matchesError(
                errorCode,
                "invalid_request_error"
        )
                || matchesError(
                errorCode,
                "not_found_error"
        )
                || matchesError(
                errorCode,
                "conflict_error"
        )
                || matchesError(
                errorCode,
                "request_too_large"
        )) {
            return AiProviderErrorType.INVALID_REQUEST;
        }

        if (status >= 500
                || matchesError(
                errorCode,
                "api_error"
        )) {
            return AiProviderErrorType.SERVER_ERROR;
        }

        return AiProviderErrorType.UNKNOWN;
    }

    private Duration calculateAttemptTimeout() {
        try {
            return properties.connectTimeout()
                    .plus(properties.readTimeout());
        } catch (ArithmeticException exception) {
            return properties.readTimeout();
        }
    }

    private static boolean matchesError(
            String actual,
            String expected
    ) {
        return expected.equals(actual);
    }

    private static String firstNonBlank(
            String first,
            String second
    ) {
        if (first != null && !first.isBlank()) {
            return first;
        }

        if (second != null && !second.isBlank()) {
            return second;
        }

        return null;
    }

    private record ParsedAnthropicResponse(
            String content,
            String messageId,
            AiResponseStatus status,
            String stopReason,
            String actualModel
    ) {
    }
}