package ru.safeai.gateway.knowledge.chunking;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;
import ru.safeai.gateway.knowledge.extraction.ExtractedSection;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class KnowledgeChunkerRegressionTest {
    private final KnowledgeChunker chunker = new KnowledgeChunker(new KnowledgeIngestionProperties(
            false, Duration.ofSeconds(2), 2, Duration.ofMinutes(3), Duration.ofSeconds(30),
            1, 3, Duration.ofSeconds(1), Duration.ofSeconds(5), 2_000,
            104_857_600L, 200, 30));

    @Test
    void emitsDeterministicHashFromActualNormalizedContentAndGlobalOrdinals() throws Exception {
        var document = new ExtractedDocument("v1", List.of(
                new ExtractedSection(1, "Heading", "First sentence. ".repeat(50)),
                new ExtractedSection(2, "Second", "Second page.")), 820);
        var first = chunker.chunk(document);
        var second = chunker.chunk(document);
        assertThat(first).containsExactlyElementsOf(second);
        assertThat(first).hasSizeGreaterThan(2);
        for (int i = 0; i < first.size(); i++) {
            var chunk = first.get(i);
            assertThat(chunk.ordinal()).isEqualTo(i);
            assertThat(chunk.contentSha256()).isEqualTo(sha256(chunk.content()));
            assertThat(chunk.estimatedTokens()).isPositive();
        }
        assertThat(first.getLast().pageFrom()).isEqualTo(2);
    }

    @Test
    void neverCutsUtf16SurrogatePairAcrossChunkBoundary() {
        var document = new ExtractedDocument("v1", List.of(
                new ExtractedSection(4, "😀", "😀".repeat(300))), 600);
        var result = chunker.chunk(document);
        assertThat(result).hasSizeGreaterThan(2);
        assertThat(result).allSatisfy(chunk -> {
            assertThat(chunk.content()).isEqualTo(
                    new String(chunk.content().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            assertThat(chunk.pageFrom()).isEqualTo(4);
            assertThat(chunk.pageTo()).isEqualTo(4);
            assertThat(chunk.content().length()).isLessThanOrEqualTo(200);
        });
    }

    @Test
    void skipsBlankSectionsButNeverEmitsEmptyDocument() {
        assertThatThrownBy(() -> chunker.chunk(new ExtractedDocument("v1",
                List.of(new ExtractedSection(1, null, "\r\n \t ")), 4)))
                .isInstanceOf(KnowledgeIngestionException.class)
                .extracting("code").isEqualTo("EMPTY_DOCUMENT");
    }

    @Test
    void capsHeadingsByUnicodeCodePointNotUtf16CodeUnit() {
        var document = new ExtractedDocument("v1", List.of(
                new ExtractedSection(1, "😀".repeat(700), "Actual text")), 11);
        var chunk = chunker.chunk(document).getFirst();
        assertThat(chunk.heading().codePointCount(0, chunk.heading().length())).isEqualTo(500);
        assertThat(chunk.heading()).isEqualTo("😀".repeat(500));
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
