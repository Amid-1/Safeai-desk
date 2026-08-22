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

@Service
public class KnowledgeChunker {

    public static final String VERSION = "safeai-char-boundary-v1";

    private final KnowledgeIngestionProperties properties;

    public KnowledgeChunker(KnowledgeIngestionProperties properties) {
        this.properties = properties;
    }

    public List<KnowledgeChunkCandidate> chunk(
            ExtractedDocument document
    ) {
        List<KnowledgeChunkCandidate> chunks = new ArrayList<>();
        int ordinal = 0;

        for (ExtractedSection section : document.sections()) {
            String text = normalize(section.text());
            if (text.isBlank()) {
                continue;
            }
            int start = 0;
            while (start < text.length()) {
                int end = chooseEnd(text, start);
                String content = text.substring(start, end).strip();
                if (!content.isBlank()) {
                    chunks.add(new KnowledgeChunkCandidate(
                            ordinal++,
                            content,
                            sha256(content),
                            estimateTokens(content),
                            section.pageNumber(),
                            section.pageNumber(),
                            truncateHeading(section.heading())
                    ));
                }
                if (end >= text.length()) {
                    break;
                }
                int next = Math.max(
                        start + 1,
                        end - properties.chunkOverlapChars()
                );
                start = skipLeadingWhitespace(text, next);
            }
        }

        if (chunks.isEmpty()) {
            throw new KnowledgeIngestionException(
                    "EMPTY_DOCUMENT",
                    "Документ не содержит извлекаемого текста",
                    false
            );
        }
        return List.copyOf(chunks);
    }

    private int chooseEnd(String text, int start) {
        int hardEnd = Math.min(
                text.length(),
                start + properties.chunkSizeChars()
        );
        if (hardEnd == text.length()) {
            return hardEnd;
        }

        int minimum = start + properties.chunkSizeChars() / 2;
        for (int index = hardEnd; index >= minimum; index--) {
            char previous = text.charAt(index - 1);
            if (previous == '\n'
                    || previous == '.'
                    || previous == '!'
                    || previous == '?') {
                return index;
            }
        }
        for (int index = hardEnd; index >= minimum; index--) {
            if (Character.isWhitespace(text.charAt(index - 1))) {
                return index;
            }
        }
        return hardEnd;
    }

    private static int skipLeadingWhitespace(String text, int start) {
        int current = start;
        while (current < text.length()
                && Character.isWhitespace(text.charAt(current))) {
            current++;
        }
        return current;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String truncateHeading(String heading) {
        if (heading == null || heading.isBlank()) {
            return null;
        }
        String normalized = heading.strip();
        return normalized.length() <= 500
                ? normalized
                : normalized.substring(0, 500);
    }

    private static int estimateTokens(String content) {
        return Math.max(1, (content.length() + 3) / 4);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
