package ru.safeai.gateway.ai.provider;

/**
 * Normalization boundary for provider-controlled identifiers and short error
 * metadata that may be logged or persisted.
 */
public final class ProviderEvidenceText {

    private ProviderEvidenceText() {
    }

    public static String optional(
            String value,
            int maxLength
    ) {
        if (maxLength < 1) {
            throw new IllegalArgumentException(
                    "maxLength должен быть положительным"
            );
        }

        if (value == null || value.isBlank()) {
            return null;
        }

        StringBuilder sanitized =
                new StringBuilder(Math.min(value.length(), maxLength));

        for (int i = 0; i < value.length()
                && sanitized.length() < maxLength; i++) {
            char character = value.charAt(i);

            if (Character.isISOControl(character)) {
                /*
                 * Do not let provider-controlled CR/LF/TAB or other control
                 * characters forge log lines or durable textual evidence.
                 */
                sanitized.append(' ');
            } else {
                sanitized.append(character);
            }
        }

        String normalized = sanitized.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String required(
            String value,
            int maxLength,
            String field
    ) {
        String normalized = optional(value, maxLength);

        if (normalized == null) {
            throw new IllegalArgumentException(
                    field + " не должен быть пустым"
            );
        }

        return normalized;
    }
}
