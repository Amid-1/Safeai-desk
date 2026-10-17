package ru.safeai.gateway.organization.service;

import org.jspecify.annotations.Nullable;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Organization name normalization, aligned with the database canonicalization
 * introduced by V73. The database UNIQUE constraint remains authoritative.
 *
 * <p>Accepted whitespace consists of Unicode category Z plus the five ASCII
 * whitespace controls (TAB, LF, VT, FF, CR). All other ISO controls are
 * rejected instead of being silently discarded by String.strip().</p>
 */
public final class OrganizationNameNormalizer {

    private static final int MAX_NAME_LENGTH = 255;

    private static final Pattern COLLAPSIBLE_WHITESPACE =
            Pattern.compile("[\\p{Z}\\s]+");

    // Quotes are treated as typography only in destructive confirmation;
    // this does not alter persisted organization names or their uniqueness.
    private static final Pattern CONFIRMATION_QUOTES =
            Pattern.compile("[\"'«»„“”‘’‚‛‹›`´]+");

    private OrganizationNameNormalizer() {
    }

    public static String canonicalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(
                    "Название организации не должно быть пустым"
            );
        }

        // Check the ORIGINAL input: strip() would otherwise hide disallowed
        // leading/trailing control characters before validation.
        rejectDisallowedControlCharacters(value);

        // Collapse before stripping: NBSP and other Unicode Z separators are
        // not necessarily removed by String.strip() at the string boundary.
        String canonical = COLLAPSIBLE_WHITESPACE
                .matcher(value)
                .replaceAll(" ")
                .strip();

        if (canonical.isBlank()) {
            throw new BadRequestException(
                    "Название организации не должно быть пустым"
            );
        }

        if (canonical.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException(
                    "Название организации не должно превышать "
                            + MAX_NAME_LENGTH + " символов"
            );
        }

        return canonical;
    }

    /**
     * Comparison key for explicit confirmation of organization disable.
     * Whitespace, casing and purely typographic quotation marks are ignored;
     * words and semantic punctuation remain significant. Version checking,
     * authorization and platform-organization protection are separate guards.
     */
    public static String normalizeForConfirmation(@Nullable String value) {
        String unicodeNormalized = Normalizer.normalize(
                canonicalize(value),
                Normalizer.Form.NFKC
        );

        String withoutQuotes = CONFIRMATION_QUOTES
                .matcher(unicodeNormalized)
                .replaceAll(" ");

        String normalized = COLLAPSIBLE_WHITESPACE
                .matcher(withoutQuotes)
                .replaceAll(" ")
                .strip()
                .toLowerCase(Locale.ROOT);

        if (normalized.isBlank()) {
            throw new BadRequestException(
                    "Название организации для подтверждения не должно быть пустым"
            );
        }

        return normalized;
    }

    private static void rejectDisallowedControlCharacters(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    && codePoint != '\t'
                    && codePoint != '\n'
                    && codePoint != '\u000B'
                    && codePoint != '\f'
                    && codePoint != '\r') {
                throw new BadRequestException(
                        "Название организации не должно содержать управляющие символы"
                );
            }
            offset += Character.charCount(codePoint);
        }
    }
}
