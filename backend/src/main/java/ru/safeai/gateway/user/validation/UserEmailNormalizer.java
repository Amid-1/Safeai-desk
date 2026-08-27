package ru.safeai.gateway.user.validation;

import org.jspecify.annotations.Nullable;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Locale;

/**
 * Единая canonicalization/validation policy для user email identity.
 *
 * <p>HTTP Bean Validation остаётся первым барьером, но не является
 * единственной границей корректности: application services могут
 * вызываться напрямую из тестов, jobs или других application flows.</p>
 *
 * <p>Canonical representation:</p>
 * <ul>
 *     <li>обычные пробелы по краям удаляются;</li>
 *     <li>lowercase выполняется через {@link Locale#ROOT};</li>
 *     <li>максимальная длина — 255 символов;</li>
 *     <li>email содержит ровно один {@code @};</li>
 *     <li>local и domain части не пусты;</li>
 *     <li>whitespace внутри email запрещён;</li>
 *     <li>ISO control characters запрещены во всём исходном значении.</li>
 * </ul>
 */
public final class UserEmailNormalizer {

    public static final int MAX_EMAIL_LENGTH = 255;

    private static final String INVALID_EMAIL_MESSAGE =
            "Некорректный формат email";

    private UserEmailNormalizer() {
    }

    /**
     * Проверяет email на application boundary.
     *
     * <p>Некорректное входное значение является ошибкой запроса.</p>
     */
    public static String normalizeAndValidate(
            @Nullable String value
    ) {
        try {
            return normalize(
                    value
            );
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(
                    INVALID_EMAIL_MESSAGE,
                    exception
            );
        }
    }

    /**
     * Проверяет email, уже находящийся во внутреннем persistence state.
     *
     * <p>Ошибка здесь означает нарушение внутреннего invariant,
     * а не ошибку пользовательского запроса.</p>
     */
    public static String normalizeStored(
            @Nullable String value
    ) {
        try {
            return normalize(
                    value
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Некорректный canonical email пользователя",
                    exception
            );
        }
    }

    private static String normalize(
            @Nullable String value
    ) {
        String raw =
                requireEmail(
                        value
                );

        /*
         * Проверяем control characters ДО trim().
         *
         * String.trim() удаляет с краёв символы <= U+0020,
         * поэтому без этой проверки trailing NUL, CR, LF и другие
         * ASCII controls могли бы быть незаметно отброшены.
         */
        rejectControlCharacters(
                raw
        );

        String normalized =
                canonicalize(
                        raw
                );

        validateCanonicalEmail(
                normalized
        );

        return normalized;
    }

    private static String requireEmail(
            @Nullable String value
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "email не должен быть null"
            );
        }

        return value;
    }

    private static String canonicalize(
            String value
    ) {
        return value
                .trim()
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private static void validateCanonicalEmail(
            String value
    ) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "email не должен быть пустым"
            );
        }

        if (value.length()
                > MAX_EMAIL_LENGTH) {

            throw new IllegalArgumentException(
                    "email не должен превышать "
                            + MAX_EMAIL_LENGTH
                            + " символов"
            );
        }

        rejectWhitespace(
                value
        );

        validateAtSign(
                value
        );
    }

    private static void rejectControlCharacters(
            String value
    ) {
        for (int offset = 0;
             offset < value.length();) {

            int codePoint =
                    value.codePointAt(
                            offset
                    );

            if (Character.isISOControl(
                    codePoint
            )) {
                throw new IllegalArgumentException(
                        "email не должен содержать "
                                + "управляющие символы"
                );
            }

            offset +=
                    Character.charCount(
                            codePoint
                    );
        }
    }

    private static void rejectWhitespace(
            String value
    ) {
        for (int offset = 0;
             offset < value.length();) {

            int codePoint =
                    value.codePointAt(
                            offset
                    );

            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)) {

                throw new IllegalArgumentException(
                        "email не должен содержать "
                                + "пробельные символы"
                );
            }

            offset +=
                    Character.charCount(
                            codePoint
                    );
        }
    }

    private static void validateAtSign(
            String value
    ) {
        int firstAt =
                value.indexOf(
                        '@'
                );

        if (firstAt <= 0
                || firstAt
                != value.lastIndexOf('@')
                || firstAt
                == value.length() - 1) {

            throw new IllegalArgumentException(
                    "email должен содержать ровно один @ "
                            + "между непустыми local/domain частями"
            );
        }
    }
}