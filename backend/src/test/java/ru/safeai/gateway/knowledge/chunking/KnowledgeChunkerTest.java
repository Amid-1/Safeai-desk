package ru.safeai.gateway.knowledge.chunking;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;
import ru.safeai.gateway.knowledge.extraction.ExtractedSection;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkerTest {

    @Test
    void preservesPageProvenanceAndProducesStableOrdinalsAndHashes() {
        KnowledgeChunker chunker = new KnowledgeChunker(properties());
        String firstPage = "Первое предложение. ".repeat(30);
        String secondPage = "Критически важный второй раздел.";

        List<KnowledgeChunkCandidate> chunks = chunker.chunk(
                new ExtractedDocument(
                        "test-v1",
                        List.of(
                                new ExtractedSection(1, "Введение", firstPage),
                                new ExtractedSection(2, "Правила", secondPage)
                        ),
                        firstPage.length() + secondPage.length()
                )
        );

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks)
                .extracting(KnowledgeChunkCandidate::ordinal)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, chunks.size())
                                .boxed()
                                .toList()
                );
        assertThat(chunks.getFirst().pageFrom()).isEqualTo(1);
        assertThat(chunks.getLast().pageFrom()).isEqualTo(2);
        assertThat(chunks)
                .allSatisfy(chunk -> assertThat(chunk.contentSha256())
                        .matches("[0-9a-f]{64}"));
    }

    private static KnowledgeIngestionProperties properties() {
        return new KnowledgeIngestionProperties(
                false,
                Duration.ofSeconds(2),
                4,
                Duration.ofMinutes(3),
                Duration.ofSeconds(60),
                2,
                5,
                Duration.ofSeconds(10),
                Duration.ofMinutes(10),
                2_000_000,
                104_857_600L,
                200,
                30
        );
    }
}
