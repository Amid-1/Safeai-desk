package ru.safeai.gateway.knowledge.chunking;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;
import ru.safeai.gateway.knowledge.extraction.ExtractedSection;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class KnowledgeChunker {

    public static final String VERSION = "safeai-char-boundary-v2";

    private static final int MAX_HEADING_CODE_POINTS = 500;

    private static final Pattern HORIZONTAL_WHITESPACE =
            Pattern.compile("[\\t\\x0B\\f ]+");

    private static final Pattern EXCESSIVE_NEWLINES =
            Pattern.compile("\\n{3,}");

    private final KnowledgeIngestionProperties properties;

    public KnowledgeChunker(
            KnowledgeIngestionProperties properties
    ) {
        this.properties = properties;
    }

    public List<KnowledgeChunkCandidate> chunk(
            ExtractedDocument document
    ) {
        List<KnowledgeChunkCandidate> chunks =
                new ArrayList<>();

        int ordinal = 0;

        for (ExtractedSection section : document.sections()) {
            String text =
                    normalize(
                            section.text()
                    );

            if (text.isBlank()) {
                continue;
            }

            int start = 0;

            while (start < text.length()) {
                int end =
                        chooseEnd(
                                text,
                                start
                        );

                String content =
                        text.substring(
                                start,
                                end
                        ).strip();

                if (!content.isBlank()) {
                    chunks.add(
                            new KnowledgeChunkCandidate(
                                    ordinal++,
                                    content,
                                    sha256(content),
                                    estimateTokens(content),
                                    section.pageNumber(),
                                    section.pageNumber(),
                                    truncateHeading(
                                            section.heading()
                                    )
                            )
                    );
                }

                if (end >= text.length()) {
                    break;
                }

                int minimumNext =
                        text.offsetByCodePoints(
                                start,
                                1
                        );

                int overlapStart =
                        toCodePointBoundary(
                                text,
                                end
                                        - properties
                                        .chunkOverlapChars()
                        );

                int next =
                        Math.max(
                                minimumNext,
                                overlapStart
                        );

                start =
                        skipLeadingWhitespace(
                                text,
                                next
                        );
            }
        }

        if (chunks.isEmpty()) {
            throw new KnowledgeIngestionException(
                    "EMPTY_DOCUMENT",
                    "Документ не содержит извлекаемого текста",
                    false
            );
        }

        return List.copyOf(
                chunks
        );
    }

    private int chooseEnd(
            String text,
            int start
    ) {
        int hardEnd =
                toCodePointBoundary(
                        text,
                        addClampedToLength(
                                text,
                                start,
                                properties.chunkSizeChars()
                        )
                );

        if (hardEnd <= start) {
            hardEnd =
                    text.offsetByCodePoints(
                            start,
                            1
                    );
        }

        if (hardEnd >= text.length()) {
            return text.length();
        }

        int minimum =
                toCodePointBoundary(
                        text,
                        addClampedToLength(
                                text,
                                start,
                                properties.chunkSizeChars()
                                        / 2
                        )
                );

        minimum =
                Math.min(
                        minimum,
                        hardEnd
                );

        int sentenceBoundary =
                findBoundaryBackward(
                        text,
                        hardEnd,
                        minimum,
                        true
                );

        if (sentenceBoundary >= 0) {
            return sentenceBoundary;
        }

        int whitespaceBoundary =
                findBoundaryBackward(
                        text,
                        hardEnd,
                        minimum,
                        false
                );

        return whitespaceBoundary >= 0
                ? whitespaceBoundary
                : hardEnd;
    }

    private static int addClampedToLength(
            String text,
            int start,
            int delta
    ) {
        if (delta <= 0) {
            return start;
        }

        int remaining =
                text.length() - start;

        return delta >= remaining
                ? text.length()
                : start + delta;
    }

    private static int findBoundaryBackward(
            String text,
            int fromExclusive,
            int minimum,
            boolean sentenceBoundary
    ) {
        int index =
                fromExclusive;

        while (index >= minimum
                && index > 0) {
            int previousCodePoint =
                    text.codePointBefore(
                            index
                    );

            if (sentenceBoundary
                    ? isSentenceBoundary(
                    previousCodePoint
            )
                    : isWhitespace(
                    previousCodePoint
            )) {
                return index;
            }

            index -=
                    Character.charCount(
                            previousCodePoint
                    );
        }

        return -1;
    }

    private static boolean isSentenceBoundary(
            int codePoint
    ) {
        return codePoint == '\n'
                || codePoint == '.'
                || codePoint == '!'
                || codePoint == '?';
    }

    private static boolean isWhitespace(
            int codePoint
    ) {
        return Character.isWhitespace(
                codePoint
        )
                || Character.isSpaceChar(
                codePoint
        );
    }

    private static int skipLeadingWhitespace(
            String text,
            int start
    ) {
        int current =
                start;

        while (current < text.length()) {
            int codePoint =
                    text.codePointAt(
                            current
                    );

            if (!isWhitespace(
                    codePoint
            )) {
                break;
            }

            current +=
                    Character.charCount(
                            codePoint
                    );
        }

        return current;
    }

    private static int toCodePointBoundary(
            String value,
            int index
    ) {
        int bounded =
                Math.clamp(
                        index,
                        0,
                        value.length()
                );

        if (bounded > 0
                && bounded < value.length()
                && Character.isHighSurrogate(
                value.charAt(
                        bounded - 1
                )
        )
                && Character.isLowSurrogate(
                value.charAt(
                        bounded
                )
        )) {
            return bounded - 1;
        }

        return bounded;
    }

    private static String normalize(
            String value
    ) {
        if (value == null) {
            return "";
        }

        String normalized =
                value.replace(
                        "\r\n",
                        "\n"
                )
                        .replace(
                                '\r',
                                '\n'
                        );

        normalized =
                HORIZONTAL_WHITESPACE
                        .matcher(
                                normalized
                        )
                        .replaceAll(
                                " "
                        );

        return EXCESSIVE_NEWLINES
                .matcher(
                        normalized
                )
                .replaceAll(
                        "\n\n"
                )
                .strip();
    }

    private static String truncateHeading(
            String heading
    ) {
        if (heading == null
                || heading.isBlank()) {
            return null;
        }

        String normalized =
                heading.strip();

        if (normalized.codePointCount(
                0,
                normalized.length()
        ) <= MAX_HEADING_CODE_POINTS) {
            return normalized;
        }

        int end =
                normalized.offsetByCodePoints(
                        0,
                        MAX_HEADING_CODE_POINTS
                );

        return normalized.substring(
                0,
                end
        );
    }

    private static int estimateTokens(
            String content
    ) {
        int codePoints =
                content.codePointCount(
                        0,
                        content.length()
                );

        return Math.max(
                1,
                (codePoints + 3) / 4
        );
    }

    private static String sha256(
            String value
    ) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest
                                    .getInstance(
                                            "SHA-256"
                                    )
                                    .digest(
                                            value.getBytes(
                                                    StandardCharsets.UTF_8
                                            )
                                    )
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }
}