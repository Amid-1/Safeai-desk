package ru.safeai.gateway.ai.provider;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AiProviderSupport {

    private static final List<String>
            PROVIDER_REQUEST_ID_HEADERS = List.of(
                    "x-request-id",
                    "request-id",
                    "openai-request-id",
                    "anthropic-request-id"
            );

    private AiProviderSupport() {
    }

    public static List<Map<String, String>> buildMessages(
            AiChatRequest request,
            Function<AiMessageRole, String> roleNormalizer
    ) {
        return buildMessages(request, roleNormalizer, Set.of());
    }

    public static List<Map<String, String>> buildMessages(
            AiChatRequest request,
            Function<AiMessageRole, String> roleNormalizer,
            Set<AiMessageRole> excludedSourceRoles
    ) {
        List<Map<String, String>> messages = new ArrayList<>();

        request.history().forEach(message -> {
            if (excludedSourceRoles.contains(message.role())) {
                return;
            }

            String role = roleNormalizer.apply(message.role());

            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException(
                        "Provider role mapping returned blank role"
                );
            }

            messages.add(Map.of(
                    "role", role,
                    "content", message.content()
            ));
        });

        messages.add(Map.of(
                "role", "user",
                "content", request.userMessage()
        ));

        return messages;
    }

    public static String extractSystemPrompt(
            AiChatRequest request
    ) {
        return request.history()
                .stream()
                .filter(message ->
                        message.role() == AiMessageRole.SYSTEM
                )
                .map(AiMessage::content)
                .collect(Collectors.joining("\n\n"));
    }

    public static Integer extractInputTokens(JsonNode response) {
        return extractNonNegativeInteger(
                response,
                "input_tokens",
                "prompt_tokens"
        );
    }

    public static Integer extractOutputTokens(JsonNode response) {
        return extractNonNegativeInteger(
                response,
                "output_tokens",
                "completion_tokens"
        );
    }

    private static Integer extractNonNegativeInteger(
            JsonNode response,
            String primaryName,
            String fallbackName
    ) {
        if (response == null) {
            return null;
        }

        JsonNode usage = response.get("usage");

        if (usage == null || !usage.isObject()) {
            return null;
        }

        JsonNode value = usage.get(primaryName);

        if (value == null || value.isNull()) {
            value = usage.get(fallbackName);
        }

        if (value == null
                || value.isNull()
                || !value.canConvertToInt()) {
            return null;
        }

        int parsed = value.intValue();
        return parsed < 0 ? null : parsed;
    }

    public static boolean isConnectFailure(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnresolvedAddressException
                    || current instanceof HttpConnectTimeoutException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    public static boolean isReadTimeout(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if ((current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException)
                    && !(current instanceof HttpConnectTimeoutException)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    public static String extractProviderRequestId(
            RestClientResponseException exception
    ) {
        HttpHeaders headers = exception.getResponseHeaders();

        if (headers == null) {
            return null;
        }

        for (String headerName : PROVIDER_REQUEST_ID_HEADERS) {
            String value = headers.getFirst(headerName);

            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    public static Duration extractRetryAfter(
            RestClientResponseException exception,
            Clock clock
    ) {
        HttpHeaders headers = exception.getResponseHeaders();

        if (headers == null) {
            return null;
        }

        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            long seconds = Long.parseLong(value.trim());

            return seconds < 0
                    ? null
                    : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            // HTTP-date variant follows.
        }

        try {
            Instant retryAt = ZonedDateTime.parse(
                    value.trim(),
                    DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant();

            Duration duration = Duration.between(
                    clock.instant(),
                    retryAt
            );

            return duration.isNegative()
                    ? Duration.ZERO
                    : duration;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public static String requireValidContent(
            String provider,
            String model,
            String content,
            int maxChars
    ) {
        if (content == null || content.isBlank()) {
            throw new AiProviderException(
                    provider,
                    model,
                    null,
                    null,
                    ru.safeai.gateway.ai.exception.AiProviderErrorType.UNKNOWN,
                    false,
                    false,
                    null,
                    provider + " provider returned empty response",
                    null
            );
        }

        if (content.length() > maxChars) {
            throw new AiProviderException(
                    provider,
                    model,
                    null,
                    null,
                    ru.safeai.gateway.ai.exception.AiProviderErrorType.UNKNOWN,
                    false,
                    false,
                    null,
                    provider + " provider response is too large",
                    null
            );
        }

        return content;
    }
}
