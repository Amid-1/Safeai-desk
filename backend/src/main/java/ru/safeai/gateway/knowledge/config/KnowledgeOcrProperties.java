package ru.safeai.gateway.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(
        prefix = "safeai.knowledge.ocr"
)
public record KnowledgeOcrProperties(
        String provider,
        String endpoint,
        String apiKey,
        List<String> allowedHosts,
        Integer minNativeCharsPerPage,
        Duration connectTimeout,
        Duration readTimeout,
        Long maxResponseBytes
) {

    private static final String PROVIDER_DISABLED =
            "disabled";

    private static final String PROVIDER_HTTP =
            "http";

    private static final Set<String> SUPPORTED_PROVIDERS =
            Set.of(
                    PROVIDER_DISABLED,
                    PROVIDER_HTTP
            );

    private static final long DEFAULT_MAX_RESPONSE_BYTES =
            16L * 1024L * 1024L;

    private static final long MIN_MAX_RESPONSE_BYTES =
            65_536L;

    private static final long MAX_MAX_RESPONSE_BYTES =
            128L * 1024L * 1024L;

    public KnowledgeOcrProperties {
        provider =
                provider == null
                        || provider.isBlank()
                        ? PROVIDER_DISABLED
                        : provider.strip()
                                .toLowerCase(
                                        Locale.ROOT
                                );

        if (!SUPPORTED_PROVIDERS.contains(
                provider
        )) {
            throw invalid(
                    "provider"
            );
        }

        allowedHosts =
                allowedHosts == null
                        ? List.of()
                        : allowedHosts.stream()
                                .filter(
                                        value ->
                                                value != null
                                                        && !value.isBlank()
                                )
                                .map(
                                        value ->
                                                value.strip()
                                                        .toLowerCase(
                                                                Locale.ROOT
                                                        )
                                )
                                .distinct()
                                .toList();

        minNativeCharsPerPage =
                minNativeCharsPerPage == null
                        ? 20
                        : minNativeCharsPerPage;

        if (minNativeCharsPerPage < 0
                || minNativeCharsPerPage > 10_000) {
            throw invalid(
                    "min-native-chars-per-page"
            );
        }

        connectTimeout =
                positive(
                        connectTimeout,
                        Duration.ofSeconds(
                                5
                        ),
                        "connect-timeout"
                );

        readTimeout =
                positive(
                        readTimeout,
                        Duration.ofMinutes(
                                2
                        ),
                        "read-timeout"
                );

        maxResponseBytes =
                maxResponseBytes == null
                        ? DEFAULT_MAX_RESPONSE_BYTES
                        : maxResponseBytes;

        if (maxResponseBytes < MIN_MAX_RESPONSE_BYTES
                || maxResponseBytes
                > MAX_MAX_RESPONSE_BYTES) {
            throw invalid(
                    "max-response-bytes"
            );
        }

        if (PROVIDER_HTTP.equals(
                provider
        )) {
            if (endpoint == null
                    || endpoint.isBlank()) {
                throw invalid(
                        "endpoint"
                );
            }

            endpoint =
                    endpoint.strip();

            URI uri =
                    parseUri(
                            endpoint
                    );

            String host =
                    uri.getHost() == null
                            ? ""
                            : uri.getHost()
                                    .toLowerCase(
                                            Locale.ROOT
                                    );

            /*
             * OCR endpoint is an outbound network security boundary.
             *
             * Requirements:
             *
             * - HTTPS only;
             * - no embedded credentials;
             * - no query parameters;
             * - no fragment;
             * - host explicitly present in allowedHosts.
             */
            if (!"https".equalsIgnoreCase(
                    uri.getScheme()
            )
                    || host.isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !allowedHosts.contains(
                            host
                    )) {
                throw new IllegalStateException(
                        "OCR endpoint должен быть HTTPS, "
                                + "не содержать user-info/query/fragment "
                                + "и входить в allowed-hosts"
                );
            }

            if (apiKey == null
                    || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "KNOWLEDGE_OCR_API_KEY обязателен "
                                + "для HTTP OCR"
                );
            }

            apiKey =
                    apiKey.strip();

        } else {
            /*
             * Disabled provider must not retain unused outbound endpoint,
             * credentials or host allow-list.
             */
            endpoint =
                    null;

            apiKey =
                    null;

            allowedHosts =
                    List.of();
        }
    }

    /**
     * This configuration contains an OCR API credential.
     *
     * <p>Never rely on the record-generated toString(), because it would expose
     * apiKey in logs, diagnostics or accidental configuration dumps.</p>
     */
    @Override
    @SuppressWarnings("NullableProblems")
    public String toString() {
        return "KnowledgeOcrProperties["
                + "provider=" + provider
                + ", endpoint=" + endpoint
                + ", apiKey=<redacted>"
                + ", allowedHosts=" + allowedHosts
                + ", minNativeCharsPerPage=" + minNativeCharsPerPage
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", maxResponseBytes=" + maxResponseBytes
                + "]";
    }

    private static URI parseUri(
            String value
    ) {
        try {
            return URI.create(
                    value
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Некорректное значение "
                            + "safeai.knowledge.ocr.endpoint",
                    exception
            );
        }
    }

    private static Duration positive(
            Duration value,
            Duration fallback,
            String name
    ) {
        Duration resolved =
                value == null
                        ? fallback
                        : value;

        if (resolved.isZero()
                || resolved.isNegative()) {
            throw invalid(
                    name
            );
        }

        return resolved;
    }

    private static IllegalStateException invalid(
            String name
    ) {
        return new IllegalStateException(
                "Некорректное значение "
                        + "safeai.knowledge.ocr."
                        + name
        );
    }
}