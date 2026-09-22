package ru.safeai.gateway.knowledge.chunking;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;
import ru.safeai.gateway.knowledge.extraction.ExtractedSection;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeStructureAwareChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker(
            new KnowledgeIngestionProperties(false, Duration.ofSeconds(2), 4,
                    Duration.ofMinutes(3), Duration.ofSeconds(60), 2, 5,
                    Duration.ofSeconds(10), Duration.ofMinutes(10),
                    2_000_000, 104_857_600L, 200, 30));

    @Test
    void markdownHeadingChangesChunkProvenanceWithoutMixingPages() {
        String text = "# Policies\n" + "A ".repeat(65) + "\n\n# Procedures\n" + "B ".repeat(65);
        List<KnowledgeChunkCandidate> chunks = chunker.chunk(
                new ExtractedDocument("test", List.of(
                        new ExtractedSection(7, "Root", text),
                        new ExtractedSection(8, "Page Two", "Факт второй страницы.")), text.length()));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks.getFirst().heading()).isEqualTo("Policies");
        assertThat(chunks.stream().anyMatch(c -> "Procedures".equals(c.heading()))).isTrue();
        assertThat(chunks.getLast().pageFrom()).isEqualTo(8);
        assertThat(chunks.getLast().pageTo()).isEqualTo(8);
        assertThat(chunks).extracting(KnowledgeChunkCandidate::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.contentSha256())
                .matches("[0-9a-f]{64}"));
    }

    @Test
    void paragraphsArePackedWithoutCrossingDistinctMarkdownHeadings() {
        String text = "# Alpha\n\n" + "alpha ".repeat(10) + "\n\n" + "again ".repeat(10)
                + "\n\n# Beta\n\n" + "beta ".repeat(10);
        List<KnowledgeChunkCandidate> chunks = chunker.chunk(
                new ExtractedDocument("test", List.of(
                        new ExtractedSection(null, null, text)), text.length()));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).heading()).isEqualTo("Alpha");
        assertThat(chunks.get(0).content()).contains("again").doesNotContain("Beta");
        assertThat(chunks.get(1).heading()).isEqualTo("Beta");
    }
}
