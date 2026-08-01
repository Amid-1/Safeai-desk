package ru.safeai.gateway.ai.provider;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import tools.jackson.databind.JsonNode;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ru.safeai.gateway.ai.provider.AiJsonNodeSupport.parseOrNull;
import static ru.safeai.gateway.ai.provider.AiJsonNodeSupport.textOrNull;

public final class AiProviderSupport {

    private static final int MAX_PROVIDER_REQUEST_ID_LENGTH =
            255;

    private static final int MAX_ERROR_TYPE_LENGTH =
            100;

    private static final int MAX_ERROR_CODE_LENGTH =
            100;

    private static final int MAX_ERROR_MESSAGE_LENGTH =
            500;

    private static final List<String> PROVIDER_REQUEST_ID_HEADERS =
            List.of(
                    "x-request-id",
                    "request-id",
                    "openai-request-id",
                    "anthropic-request-id"
            );

    private AiProviderSupport() {
    }

    public static List<Map<String, String>> buildOpenAiInput(
            AiChatRequest request
    ) {
        List<Map<String, String>> messages =
                new ArrayList<>();

        addInstruction(
                messages,
                "system",
                request.systemInstructions()
        );

        addInstruction(
                messages,
                "developer",
                request.developerInstructions()
        );

        for (AiMessage message : request.history()) {
            messages.add(
                    message(
                            message.role().providerValue(),
                            message.content()
                    )
            );
        }

        messages.add(
                message(
                        "user",
                        request.userMessage()
                )
        );

        return List.copyOf(messages);
    }

    public static List<Map<String, String>> buildAnthropicMessages(
            AiChatRequest request
    ) {
        List<Map<String, String>> messages =
                new ArrayList<>();

        for (AiMessage message : request.history()) {
            String role =
                    message.role() == AiMessageRole.ASSISTANT
                            ? "assistant"
                            : "user";

            messages.add(
                    message(
                            role,
                            message.content()
                    )
            );
        }

        messages.add(
                message(
                        "user",
                        request.userMessage()
                )
        );

        return List.copyOf(messages);
    }

    public static List<Map<String, String>> buildAnthropicSystem(
            AiChatRequest request
    ) {
        List<Map<String, String>> blocks =
                new ArrayList<>();

        addTextBlock(
                blocks,
                request.systemInstructions()
        );

        addTextBlock(
                blocks,
                request.developerInstructions()
        );

        return List.copyOf(blocks);
    }

    public static Integer extractInputTokens(
            JsonNode response
    ) {
        return extractNonNegativeInteger(
                response,
                "input_tokens",
                "prompt_tokens"
        );
    }

    public static Integer extractOutputTokens(
            JsonNode response
    ) {
        return extractNonNegativeInteger(
                response,
                "output_tokens",
                "completion_tokens"
        );
    }

    public static boolean isConnectFailure(
            Throwable exception
    ) {
        return hasCause(
                exception,
                ConnectException.class,
                UnknownHostException.class,
                NoRouteToHostException.class,
                UnresolvedAddressException.class,
                HttpConnectTimeoutException.class
        );
    }

    public static boolean isReadTimeout(
            Throwable exception
    ) {
        Throwable current =
                exception;

        while (current != null) {
            boolean timeout =
                    current instanceof SocketTimeoutException
                            || current instanceof HttpTimeoutException;

            boolean connectTimeout =
                    current instanceof HttpConnectTimeoutException;

            if (timeout && !connectTimeout) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    public static boolean isResponseTooLarge(
            Throwable exception
    ) {
        return hasCause(
                exception,
                AiResponseTooLargeIOException.class
        );
    }

    public static String extractProviderRequestId(
            RestClientResponseException exception
    ) {
        String fromHeaders =
                extractProviderRequestId(
                        exception.getResponseHeaders()
                );

        if (fromHeaders != null) {
            return fromHeaders;
        }

        JsonNode body =
                parseOrNull(
                        exception.getResponseBodyAsString()
                );

        if (body == null) {
            return null;
        }

        return normalizeProviderRequestId(
                textOrNull(
                        body.get("request_id")
                )
        );
    }

    public static String extractProviderRequestId(
            HttpHeaders headers
    ) {
        if (headers == null) {
            return null;
        }

        for (String headerName : PROVIDER_REQUEST_ID_HEADERS) {
            String requestId =
                    normalizeProviderRequestId(
                            headers.getFirst(headerName)
                    );

            if (requestId != null) {
                return requestId;
            }
        }

        return null;
    }

    public static Duration extractRetryAfter(
            RestClientResponseException exception,
            Clock clock
    ) {
        HttpHeaders headers =
                exception.getResponseHeaders();

        if (headers == null) {
            return null;
        }

        String value =
                headers.getFirst(
                        HttpHeaders.RETRY_AFTER
                );

        if (value == null || value.isBlank()) {
            return null;
        }

        String normalizedValue =
                value.trim();

        Duration deltaSeconds =
                parseRetryAfterSeconds(
                        normalizedValue
                );

        if (deltaSeconds != null) {
            return deltaSeconds;
        }

        return parseRetryAfterDate(
                normalizedValue,
                clock
        );
    }

    public static ProviderErrorDetails extractProviderError(
            RestClientResponseException exception
    ) {
        JsonNode root =
                parseOrNull(
                        exception.getResponseBodyAsString()
                );

        if (root == null) {
            return ProviderErrorDetails.EMPTY;
        }

        JsonNode error =
                root.get("error");

        if (error == null || !error.isObject()) {
            return new ProviderErrorDetails(
                    textOrNull(root.get("type")),
                    textOrNull(root.get("code")),
                    textOrNull(root.get("message"))
            );
        }

        return new ProviderErrorDetails(
                textOrNull(error.get("type")),
                textOrNull(error.get("code")),
                textOrNull(error.get("message"))
        );
    }

    public static String requireValidContent(
            String provider,
            String model,
            String content,
            int maxChars
    ) {
        if (content == null || content.isBlank()) {
            throw protocolException(
                    provider,
                    model,
                    AiProviderErrorType.PROTOCOL_ERROR,
                    provider
                            + " provider returned empty response"
            );
        }

        if (content.length() > maxChars) {
            throw responseTooLargeException(
                    provider,
                    model
            );
        }

        return content;
    }

    public static void appendBoundedText(
            StringBuilder target,
            String value,
            int maxChars,
            String provider,
            String model
    ) {
        if (value == null || value.isEmpty()) {
            return;
        }

        int separatorLength =
                target.isEmpty()
                        ? 0
                        : 2;

        long resultingLength =
                (long) target.length()
                        + separatorLength
                        + value.length();

        if (resultingLength > maxChars) {
            throw responseTooLargeException(
                    provider,
                    model
            );
        }

        if (separatorLength > 0) {
            target.append("\n\n");
        }

        target.append(value);
    }

    public static String resolvedModel(
            JsonNode response,
            String requestedModel
    ) {
        String actualModel =
                response == null
                        ? null
                        : textOrNull(
                                response.get("model")
                        );

        return actualModel == null
                ? requestedModel
                : actualModel;
    }

    private static Duration parseRetryAfterSeconds(
            String value
    ) {
        try {
            long seconds =
                    Long.parseLong(value);

            if (seconds < 0) {
                return null;
            }

            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Duration parseRetryAfterDate(
            String value,
            Clock clock
    ) {
        try {
            Instant retryAt =
                    ZonedDateTime.parse(
                            value,
                            DateTimeFormatter.RFC_1123_DATE_TIME
                    ).toInstant();

            Duration duration =
                    Duration.between(
                            clock.instant(),
                            retryAt
                    );

            return duration.isNegative()
                    ? Duration.ZERO
                    : duration;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Integer extractNonNegativeInteger(
            JsonNode response,
            String primaryName,
            String fallbackName
    ) {
        if (response == null) {
            return null;
        }

        JsonNode usage =
                response.get("usage");

        if (usage == null || !usage.isObject()) {
            return null;
        }

        JsonNode value =
                usage.get(primaryName);

        if (value == null || value.isNull()) {
            value = usage.get(fallbackName);
        }

        if (value == null
                || value.isNull()
                || !value.canConvertToInt()) {
            return null;
        }

        int parsed =
                value.intValue();

        return parsed < 0
                ? null
                : parsed;
    }

    @SafeVarargs
    private static boolean hasCause(
            Throwable exception,
            Class<? extends Throwable>... types
    ) {
        Throwable current =
                exception;

        while (current != null) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(current)) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    private static void addInstruction(
            List<Map<String, String>> messages,
            String role,
            String content
    ) {
        if (content == null || content.isBlank()) {
            return;
        }

        messages.add(
                message(
                        role,
                        content
                )
        );
    }

    private static void addTextBlock(
            List<Map<String, String>> blocks,
            String content
    ) {
        if (content == null || content.isBlank()) {
            return;
        }

        Map<String, String> block =
                new LinkedHashMap<>();

        block.put(
                "type",
                "text"
        );

        block.put(
                "text",
                content
        );

        blocks.add(
                Map.copyOf(block)
        );
    }

    private static Map<String, String> message(
            String role,
            String content
    ) {
        Map<String, String> message =
                new LinkedHashMap<>();

        message.put(
                "role",
                role
        );

        message.put(
                "content",
                content
        );

        return Map.copyOf(message);
    }

    private static String normalizeProviderRequestId(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized =
                value.trim();

        if (normalized.length()
                <= MAX_PROVIDER_REQUEST_ID_LENGTH) {
            return normalized;
        }

        return normalized.substring(
                0,
                MAX_PROVIDER_REQUEST_ID_LENGTH
        );
    }

    private static AiProviderException responseTooLargeException(
            String provider,
            String model
    ) {
        return protocolException(
                provider,
                model,
                AiProviderErrorType.RESPONSE_TOO_LARGE,
                provider
                        + " provider response is too large"
        );
    }

    private static AiProviderException protocolException(
            String provider,
            String model,
            AiProviderErrorType errorType,
            String message
    ) {
        return new AiProviderException(
                provider,
                model,
                null,
                null,
                errorType,
                false,
                false,
                null,
                message,
                null
        );
    }

    public record ProviderErrorDetails(
            String type,
            String code,
            String message
    ) {
        private static final ProviderErrorDetails EMPTY =
                new ProviderErrorDetails(
                        null,
                        null,
                        null
                );

        public ProviderErrorDetails {
            type = normalize(
                    type,
                    MAX_ERROR_TYPE_LENGTH
            );

            code = normalize(
                    code,
                    MAX_ERROR_CODE_LENGTH
            );

            message = normalize(
                    message,
                    MAX_ERROR_MESSAGE_LENGTH
            );
        }

        private static String normalize(
                String value,
                int maxLength
        ) {
            if (value == null || value.isBlank()) {
                return null;
            }

            String normalized =
                    value.trim();

            if (normalized.length() <= maxLength) {
                return normalized;
            }

            return normalized.substring(
                    0,
                    maxLength
            );
        }
    }
}