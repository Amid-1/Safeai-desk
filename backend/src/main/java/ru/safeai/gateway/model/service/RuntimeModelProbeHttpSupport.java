package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Hardened HTTP helper for metadata-only runtime model probes.
 *
 * <p>Response bodies are discarded and redirects are disabled so credentials
 * cannot be forwarded to an unexpected host.</p>
 *
 * <p>The helper creates a short-lived {@link HttpClient} for one explicit
 * administrative probe and closes it deterministically via try-with-resources.
 * Runtime probes are infrequent administrative operations and are not part of
 * the high-throughput provider data plane.</p>
 */
final class RuntimeModelProbeHttpSupport {

    private static final Duration MIN_TIMEOUT =
            Duration.ofSeconds(1);

    private static final Duration MAX_TIMEOUT =
            Duration.ofSeconds(10);

    private RuntimeModelProbeHttpSupport() {
    }

    static RuntimeModelProbeResult probeModel(
            String provider,
            String model,
            String baseUrl,
            Map<String, String> headers,
            Duration connectTimeout,
            Duration readTimeout,
            Clock clock
    ) {
        Objects.requireNonNull(
                headers,
                "headers не должен быть null"
        );

        Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );

        HttpRequest request =
                buildRequest(
                        modelUri(
                                baseUrl,
                                model
                        ),
                        headers,
                        boundedTimeout(
                                readTimeout
                        )
                );

        long startedNanos =
                System.nanoTime();

        try (
                HttpClient client =
                        HttpClient.newBuilder()
                                .connectTimeout(
                                        boundedTimeout(
                                                connectTimeout
                                        )
                                )
                                .followRedirects(
                                        HttpClient.Redirect.NEVER
                                )
                                .build()
        ) {
            return executeProbe(
                    client,
                    request,
                    provider,
                    model,
                    clock,
                    startedNanos
            );
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();

            return unavailable(
                    provider,
                    model,
                    clock,
                    startedNanos,
                    "Проверка была прервана до ответа провайдера"
            );
        } catch (IOException exception) {
            return unavailable(
                    provider,
                    model,
                    clock,
                    startedNanos,
                    "Не удалось установить соединение с провайдером"
            );
        } catch (RuntimeException exception) {
            return internalError(
                    provider,
                    model,
                    clock,
                    startedNanos
            );
        }
    }

    private static RuntimeModelProbeResult executeProbe(
            HttpClient client,
            HttpRequest request,
            String provider,
            String model,
            Clock clock,
            long startedNanos
    ) throws IOException, InterruptedException {
        HttpResponse<Void> response =
                client.send(
                        request,
                        HttpResponse.BodyHandlers.discarding()
                );

        int status =
                response.statusCode();

        return new RuntimeModelProbeResult(
                provider,
                model,
                mapStatus(
                        status
                ),
                Instant.now(
                        clock
                ),
                elapsedMillis(
                        startedNanos
                ),
                status,
                publicMessage(
                        status
                )
        );
    }

    private static HttpRequest buildRequest(
            URI uri,
            Map<String, String> headers,
            Duration readTimeout
    ) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(
                                uri
                        )
                        .GET()
                        .timeout(
                                readTimeout
                        )
                        .header(
                                "Accept",
                                "application/json"
                        );

        headers.forEach(
                (name, value) ->
                        addValidatedHeader(
                                builder,
                                name,
                                value
                        )
        );

        return builder.build();
    }

    private static void addValidatedHeader(
            HttpRequest.Builder builder,
            String name,
            String value
    ) {
        if (
                name == null
                || name.isBlank()
                || value == null
                || value.isBlank()
        ) {
            throw new IllegalStateException(
                    "Runtime probe содержит пустой HTTP header"
            );
        }

        builder.header(
                name,
                value
        );
    }

    private static URI modelUri(
            String baseUrl,
            String model
    ) {
        URI baseUri =
                validatedBaseUri(
                        baseUrl
                );

        return URI.create(
                baseUri.toASCIIString()
                        + "/models/"
                        + encodeModel(
                                model
                        )
        );
    }

    private static URI validatedBaseUri(
            String baseUrl
    ) {
        URI uri =
                URI.create(
                        normalizeBaseUrl(
                                baseUrl
                        )
                );

        validateBaseUri(
                uri
        );

        return uri;
    }

    private static String normalizeBaseUrl(
            String baseUrl
    ) {
        return stripTrailingSlashes(
                requireText(
                        baseUrl,
                        "baseUrl"
                )
        );
    }

    private static String stripTrailingSlashes(
            String value
    ) {
        int end =
                value.length();

        while (
                end > 0
                && value.charAt(
                        end - 1
                ) == '/'
        ) {
            end--;
        }

        if (end == 0) {
            throw new IllegalStateException(
                    "Runtime probe baseUrl не должен состоять только из '/'"
            );
        }

        return value.substring(
                0,
                end
        );
    }

    private static void validateBaseUri(
            URI uri
    ) {
        if (
                !"https".equalsIgnoreCase(
                        uri.getScheme()
                )
        ) {
            throw new IllegalStateException(
                    "Runtime probe разрешён только через HTTPS"
            );
        }

        if (
                uri.getHost() == null
                || uri.getHost().isBlank()
        ) {
            throw new IllegalStateException(
                    "Runtime probe baseUrl должен содержать корректный host"
            );
        }

        if (uri.getUserInfo() != null) {
            throw new IllegalStateException(
                    "Runtime probe baseUrl не должен содержать user-info"
            );
        }

        if (
                uri.getQuery() != null
                || uri.getFragment() != null
        ) {
            throw new IllegalStateException(
                    "Runtime probe baseUrl не должен содержать query или fragment"
            );
        }
    }

    private static String encodeModel(
            String model
    ) {
        return URLEncoder.encode(
                        requireText(
                                model,
                                "model"
                        ),
                        StandardCharsets.UTF_8
                )
                .replace(
                        "+",
                        "%20"
                );
    }

    private static String requireText(
            String value,
            String field
    ) {
        String text =
                Objects.requireNonNull(
                                value,
                                field
                                        + " не должен быть null"
                        )
                        .trim();

        if (text.isEmpty()) {
            throw new IllegalStateException(
                    field
                            + " не должен быть пустым"
            );
        }

        return text;
    }

    private static Duration boundedTimeout(
            Duration value
    ) {
        if (
                value == null
                || value.isZero()
                || value.isNegative()
        ) {
            return MIN_TIMEOUT;
        }

        if (
                value.compareTo(
                        MIN_TIMEOUT
                ) < 0
        ) {
            return MIN_TIMEOUT;
        }

        if (
                value.compareTo(
                        MAX_TIMEOUT
                ) > 0
        ) {
            return MAX_TIMEOUT;
        }

        return value;
    }

    private static RuntimeModelProbeResult unavailable(
            String provider,
            String model,
            Clock clock,
            long startedNanos,
            String message
    ) {
        return new RuntimeModelProbeResult(
                provider,
                model,
                RuntimeModelProbeStatus.UNAVAILABLE,
                Instant.now(
                        clock
                ),
                elapsedMillis(
                        startedNanos
                ),
                null,
                message
        );
    }

    private static RuntimeModelProbeResult internalError(
            String provider,
            String model,
            Clock clock,
            long startedNanos
    ) {
        return new RuntimeModelProbeResult(
                provider,
                model,
                RuntimeModelProbeStatus.ERROR,
                Instant.now(
                        clock
                ),
                elapsedMillis(
                        startedNanos
                ),
                null,
                "Проверка завершилась внутренней ошибкой"
        );
    }

    private static long elapsedMillis(
            long startedNanos
    ) {
        return Math.max(
                0L,
                Duration.ofNanos(
                        System.nanoTime()
                                - startedNanos
                ).toMillis()
        );
    }

    private static RuntimeModelProbeStatus mapStatus(
            int status
    ) {
        if (
                status >= 200
                && status < 300
        ) {
            return RuntimeModelProbeStatus.AVAILABLE;
        }

        return switch (status) {
            case 401, 403 ->
                    RuntimeModelProbeStatus.AUTH_ERROR;

            case 404 ->
                    RuntimeModelProbeStatus.MODEL_NOT_FOUND;

            case 429 ->
                    RuntimeModelProbeStatus.RATE_LIMITED;

            default ->
                    status >= 500
                            ? RuntimeModelProbeStatus.UNAVAILABLE
                            : RuntimeModelProbeStatus.ERROR;
        };
    }

    private static String publicMessage(
            int status
    ) {
        if (
                status >= 200
                && status < 300
        ) {
            return "Провайдер подтвердил доступность модели";
        }

        return switch (status) {
            case 401, 403 ->
                    "Провайдер отклонил учётные данные";

            case 404 ->
                    "Провайдер не нашёл настроенную модель";

            case 429 ->
                    "Провайдер временно ограничил запросы";

            default ->
                    status >= 500
                            ? "Провайдер временно недоступен"
                            : "Провайдер вернул неожиданный статус";
        };
    }
}
