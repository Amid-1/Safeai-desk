package ru.safeai.gateway.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "safeai.knowledge.ocr")
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
    public KnowledgeOcrProperties {
        provider = provider == null || provider.isBlank()
                ? "disabled"
                : provider.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("disabled", "http").contains(provider)) {
            throw invalid("provider");
        }
        allowedHosts = allowedHosts == null
                ? List.of()
                : allowedHosts.stream()
                        .map(value -> value.strip().toLowerCase(Locale.ROOT))
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
        minNativeCharsPerPage = minNativeCharsPerPage == null
                ? 20
                : minNativeCharsPerPage;
        if (minNativeCharsPerPage < 0 || minNativeCharsPerPage > 10_000) {
            throw invalid("min-native-chars-per-page");
        }
        connectTimeout = positive(
                connectTimeout,
                Duration.ofSeconds(5),
                "connect-timeout"
        );
        readTimeout = positive(
                readTimeout,
                Duration.ofMinutes(2),
                "read-timeout"
        );
        maxResponseBytes = maxResponseBytes == null
                ? 16L * 1024L * 1024L
                : maxResponseBytes;
        if (maxResponseBytes < 65_536L
                || maxResponseBytes > 128L * 1024L * 1024L) {
            throw invalid("max-response-bytes");
        }

        if ("http".equals(provider)) {
            if (endpoint == null || endpoint.isBlank()) {
                throw invalid("endpoint");
            }
            endpoint = endpoint.strip();
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null
                    ? ""
                    : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || !allowedHosts.contains(host)) {
                throw new IllegalStateException(
                        "OCR endpoint должен быть HTTPS и входить в allowed-hosts"
                );
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "KNOWLEDGE_OCR_API_KEY обязателен для HTTP OCR"
                );
            }
            apiKey = apiKey.strip();
        } else {
            endpoint = null;
            apiKey = null;
        }
    }

    private static Duration positive(
            Duration value,
            Duration fallback,
            String name
    ) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw invalid(name);
        }
        return resolved;
    }

    private static IllegalStateException invalid(String name) {
        return new IllegalStateException(
                "Некорректное значение safeai.knowledge.ocr." + name
        );
    }
}
