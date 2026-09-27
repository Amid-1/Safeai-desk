// backend/src/main/java/ru/safeai/gateway/chat/title/ChatTitlePolicy.java
package ru.safeai.gateway.chat.title;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure deterministic policy for chat titles.
 *
 * <p>No provider/LLM call is performed here. Chat naming therefore adds no
 * hidden model invocation, spend, retry path or execution-attempt evidence.</p>
 */
public final class ChatTitlePolicy {

    public static final String DEFAULT_TITLE = "Новый чат";
    public static final int MAX_STORED_TITLE_CODE_POINTS = 255;
    public static final int MAX_AUTOMATIC_TITLE_CODE_POINTS = 72;

    private static final int MIN_USEFUL_SENTENCE_CODE_POINTS = 18;
    private static final int MIN_WORD_BOUNDARY_PREFIX_CODE_POINTS = 28;

    private static final Pattern WHITESPACE =
            Pattern.compile("[\\p{Z}\\s]+");

    private static final Pattern LEADING_MARKUP = Pattern.compile(
            "^(?:(?:#{1,6}|[>*+\\-]|\\d{1,4}[.)])\\s+)+"
    );

    private static final Pattern GREETING_PREFIX = Pattern.compile(
            "(?iu)^(?:привет(?:ствую)?|здравствуйте|добрый\\s+(?:день|вечер|утро)|hello|hi|hey)"
                    + "(?:\\s*[,!:.—-]+\\s*|\\s+)"
    );

    private static final Pattern SENTENCE_END =
            Pattern.compile("[.!?…](?:\\s|$)");

    private static final Pattern TRAILING_FRAGMENT =
            Pattern.compile("[\\p{P}\\s]+$");

    private ChatTitlePolicy() {
    }

    public static String normalizeCreationTitle(String value) {
        return value == null || value.isBlank()
                ? DEFAULT_TITLE
                : normalizeExplicitTitle(value);
    }

    public static String normalizeStoredTitle(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_TITLE;
        }

        return normalizeExplicitTitle(value);
    }

    public static String normalizeManualTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Название чата не должно быть пустым"
            );
        }

        return normalizeExplicitTitle(value);
    }

    public static String automaticTitle(String firstUserMessage) {
        Objects.requireNonNull(
                firstUserMessage,
                "firstUserMessage не должен быть null"
        );

        String normalized =
                normalizeWhitespace(firstUserMessage);

        if (normalized.isEmpty()) {
            return DEFAULT_TITLE;
        }

        String withoutMarkup =
                LEADING_MARKUP
                        .matcher(normalized)
                        .replaceFirst("");

        String withoutGreeting =
                GREETING_PREFIX
                        .matcher(withoutMarkup)
                        .replaceFirst("");

        String candidate =
                withoutGreeting.isBlank()
                        ? withoutMarkup
                        : withoutGreeting;

        if (candidate.isBlank()) {
            candidate = normalized;
        }

        String sentence =
                firstUsefulSentence(candidate);

        if (sentence != null) {
            candidate = sentence;
        }

        return truncateAutomaticTitle(candidate);
    }

    private static String normalizeExplicitTitle(String value) {
        String normalized =
                normalizeWhitespace(value);

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Название чата не должно быть пустым"
            );
        }

        int codePoints =
                normalized.codePointCount(
                        0,
                        normalized.length()
                );

        if (codePoints > MAX_STORED_TITLE_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Название чата не должно превышать "
                            + MAX_STORED_TITLE_CODE_POINTS
                            + " символов"
            );
        }

        return normalized;
    }

    private static String normalizeWhitespace(String value) {
        return WHITESPACE
                .matcher(value)
                .replaceAll(" ")
                .trim();
    }

    private static String firstUsefulSentence(String value) {
        Matcher matcher =
                SENTENCE_END.matcher(value);

        while (matcher.find()) {
            int end =
                    matcher.start() + 1;

            String sentence =
                    value.substring(0, end).trim();

            int length =
                    sentence.codePointCount(
                            0,
                            sentence.length()
                    );

            if (length >= MIN_USEFUL_SENTENCE_CODE_POINTS
                    && length <= MAX_AUTOMATIC_TITLE_CODE_POINTS) {
                return sentence;
            }

            if (length > MAX_AUTOMATIC_TITLE_CODE_POINTS) {
                return null;
            }
        }

        return null;
    }

    private static String truncateAutomaticTitle(String value) {
        String normalized = value.trim();

        int codePoints =
                normalized.codePointCount(
                        0,
                        normalized.length()
                );

        if (codePoints <= MAX_AUTOMATIC_TITLE_CODE_POINTS) {
            return normalized;
        }

        int prefixLimit =
                MAX_AUTOMATIC_TITLE_CODE_POINTS - 1;

        int end =
                normalized.offsetByCodePoints(
                        0,
                        prefixLimit
                );

        String prefix =
                normalized
                        .substring(0, end)
                        .stripTrailing();

        int lastSpace =
                prefix.lastIndexOf(' ');

        if (lastSpace > 0) {
            String wordBoundary =
                    prefix
                            .substring(0, lastSpace)
                            .stripTrailing();

            int wordBoundaryLength =
                    wordBoundary.codePointCount(
                            0,
                            wordBoundary.length()
                    );

            if (wordBoundaryLength
                    >= MIN_WORD_BOUNDARY_PREFIX_CODE_POINTS) {
                prefix = wordBoundary;
            }
        }

        prefix =
                TRAILING_FRAGMENT
                        .matcher(prefix)
                        .replaceFirst("")
                        .stripTrailing();

        if (prefix.isEmpty()) {
            prefix =
                    normalized
                            .substring(0, end)
                            .stripTrailing();
        }

        return prefix + "…";
    }
}
