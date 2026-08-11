package ru.safeai.gateway.organization.service;

import org.jspecify.annotations.Nullable;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class OrganizationNameNormalizer {

    private static final int MAX_NAME_LENGTH = 255;

    private static final Pattern COLLAPSIBLE_WHITESPACE =
            Pattern.compile(
                    "[\\p{Z}\\s]+"
            );

    /*
     * Для destructive confirmation кавычки являются только
     * типографическим оформлением и не меняют идентичность имени.
     *
     * Намеренно НЕ удаляем дефисы, точки, скобки и другие символы:
     * "ООО Альфа-Сервис" и "ООО Альфа Сервис" должны оставаться
     * разными подтверждениями.
     */
    private static final Pattern CONFIRMATION_QUOTES =
            Pattern.compile(
                    "[\"'«»„“”‘’‚‛‹›`´]+"
            );

    private OrganizationNameNormalizer() {
    }

    public static String canonicalize(
            @Nullable String value
    ) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(
                    "Название организации не должно быть пустым"
            );
        }

        String canonical =
                COLLAPSIBLE_WHITESPACE
                        .matcher(value.strip())
                        .replaceAll(" ");

        if (canonical.isBlank()) {
            throw new BadRequestException(
                    "Название организации не должно быть пустым"
            );
        }

        if (canonical.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException(
                    "Название организации не должно превышать "
                            + MAX_NAME_LENGTH
                            + " символов"
            );
        }

        return canonical;
    }

    /**
     * Нормализация имени для уникальности и обычного сравнения.
     *
     * <p>Эту семантику нельзя смешивать с destructive confirmation:
     * здесь кавычки остаются частью имени.</p>
     */
    public static String normalize(
            @Nullable String value
    ) {
        return canonicalize(value)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Нормализация исключительно для подтверждения опасной операции.
     *
     * <p>Игнорируются:</p>
     * <ul>
     *     <li>регистр;</li>
     *     <li>двойные, одинарные и типографские кавычки;</li>
     *     <li>лишние ASCII/Unicode-пробелы.</li>
     * </ul>
     *
     * <p>Не игнорируются буквы, слова, организационно-правовая форма,
     * дефисы и прочая смысловая пунктуация. Поэтому "ООО Зил"
     * не совпадёт с "ООО Зел", "ООО Зилл", "ООО Зио",
     * "АО Зил" или просто "Зил".</p>
     */
    public static String normalizeForConfirmation(
            @Nullable String value
    ) {
        String unicodeNormalized =
                Normalizer.normalize(
                        canonicalize(value),
                        Normalizer.Form.NFKC
                );

        String withoutQuotes =
                CONFIRMATION_QUOTES
                        .matcher(unicodeNormalized)
                        .replaceAll(" ");

        String normalized =
                COLLAPSIBLE_WHITESPACE
                        .matcher(withoutQuotes.strip())
                        .replaceAll(" ")
                        .toLowerCase(Locale.ROOT);

        if (normalized.isBlank()) {
            throw new BadRequestException(
                    "Название организации для подтверждения "
                            + "не должно быть пустым"
            );
        }

        return normalized;
    }
}
