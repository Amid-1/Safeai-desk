package ru.safeai.gateway.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.audit.details")
public record AuditDetailsProperties(
        Integer maxDepth,
        Integer maxContainerItems,
        Integer maxTotalNodes,
        Integer maxTotalStringChars,
        Integer maxStringLength,
        Integer maxJsonBytes,
        Integer maxNumberTextLength
) {
    public AuditDetailsProperties {
        maxDepth = value(maxDepth, 4);
        maxContainerItems = value(maxContainerItems, 100);
        maxTotalNodes = value(maxTotalNodes, 500);
        maxTotalStringChars = value(
                maxTotalStringChars,
                24_000
        );
        maxStringLength = value(maxStringLength, 1_024);
        maxJsonBytes = value(maxJsonBytes, 60 * 1_024);
        maxNumberTextLength = value(
                maxNumberTextLength,
                256
        );

        range(maxDepth, 1, 16, "max-depth");
        range(
                maxContainerItems,
                1,
                10_000,
                "max-container-items"
        );
        range(
                maxTotalNodes,
                10,
                100_000,
                "max-total-nodes"
        );
        range(
                maxTotalStringChars,
                100,
                1_000_000,
                "max-total-string-chars"
        );
        range(
                maxStringLength,
                16,
                100_000,
                "max-string-length"
        );
        range(
                maxJsonBytes,
                1_024,
                60 * 1_024,
                "max-json-bytes"
        );
        range(
                maxNumberTextLength,
                16,
                10_000,
                "max-number-text-length"
        );
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static void range(
            int value,
            int min,
            int max,
            String property
    ) {
        if (value < min || value > max) {
            throw new IllegalStateException(
                    "safeai.audit.details."
                            + property
                            + " должен быть в диапазоне "
                            + min
                            + "–"
                            + max
            );
        }
    }
}
