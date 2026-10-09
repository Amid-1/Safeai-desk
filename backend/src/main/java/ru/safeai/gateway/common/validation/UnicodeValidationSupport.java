package ru.safeai.gateway.common.validation;

import java.util.Objects;

/**
 * Shared strict validation for Java UTF-16 strings.
 *
 * <p>Rejects unpaired high/low surrogate code units. Unicode normalization is
 * intentionally not performed here: each caller chooses its own normalization
 * form according to its domain contract (for example NFC for identity
 * snapshots and NFKC for security-oriented canonicalization).</p>
 */
public final class UnicodeValidationSupport {

    private UnicodeValidationSupport() {
    }

    public static void requireWellFormedUtf16(
            String value,
            String fieldName
    ) {
        Objects.requireNonNull(
                value,
                "value не должен быть null"
        );

        Objects.requireNonNull(
                fieldName,
                "fieldName не должен быть null"
        );

        for (int index = 0;
             index < value.length();
             index++) {

            char current =
                    value.charAt(
                            index
                    );

            if (Character.isHighSurrogate(
                    current
            )) {
                if (index + 1
                        >= value.length()
                        || !Character.isLowSurrogate(
                        value.charAt(
                                index + 1
                        )
                )) {
                    throw invalidUnicode(
                            fieldName
                    );
                }

                index++;
                continue;
            }

            if (Character.isLowSurrogate(
                    current
            )) {
                throw invalidUnicode(
                        fieldName
                );
            }
        }
    }

    private static IllegalArgumentException invalidUnicode(
            String fieldName
    ) {
        return new IllegalArgumentException(
                fieldName
                        + " содержит некорректный Unicode"
        );
    }
}
