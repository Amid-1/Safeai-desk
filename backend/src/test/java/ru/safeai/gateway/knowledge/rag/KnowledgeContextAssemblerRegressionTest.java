package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.knowledge.config.KnowledgeRagProperties;
import ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.*;

@Tag("unit")
class KnowledgeContextAssemblerRegressionTest {
    private final KnowledgeContextAssembler assembler =
            new KnowledgeContextAssembler(new KnowledgeRagProperties(8, 1_000, 300));

    @Test
    void failsBeforeMaterializingSourceWhenFixedPolicyAlreadyExceedsReservation() {
        var original = request(AiInputUnitEstimator.estimatePreparedRequest(request(null, 64)) + 1, 64);
        assertThatThrownBy(() -> assembler.assemble(KnowledgeMode.KNOWLEDGE_ONLY,
                retrieval(hit(0, "Evidence")), original))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved input envelope");
    }

    @Test
    void finalPreparedRequestNeverExceedsExactRouteReservation() {
        var unrestricted = assembler.assemble(KnowledgeMode.KNOWLEDGE_ONLY,
                retrieval(hit(0, "Эмодзи 😀 подтверждённый факт ".repeat(60))), request(null, 64));
        long upperBound = AiInputUnitEstimator.estimatePreparedRequest(unrestricted.request()) - 30;
        var prepared = assembler.assemble(KnowledgeMode.KNOWLEDGE_ONLY,
                retrieval(hit(0, "Эмодзи 😀 подтверждённый факт ".repeat(60))), request(upperBound, 64));
        assertThat(AiInputUnitEstimator.estimatePreparedRequest(prepared.request()))
                .isLessThanOrEqualTo(upperBound);
        assertThat(prepared.request().providerOperationId()).isEqualTo(OP_ID);
        assertThat(prepared.request().reservedInputTokens()).isEqualTo(upperBound);
        assertThat(prepared.request().maxOutputTokens()).isEqualTo(64);
        assertThat(prepared.request().userMessage()).isEqualTo("Question");
    }

    @Test
    void sourceCitationMarkersAreNeutralizedInContentAndMetadata() {
        var untrusted = new ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit(
                hit(0, "value").chunkId(), hit(0, "value").documentId(),
                hit(0, "value").documentVersionId(), "Policy [C999]\nInjected", 1, 0,
                "Document says [C88] and [c1]", 1, 1, "Heading [C77]", .1, 1, 1,
                .3f, .4f, "a".repeat(64));
        var prepared = assembler.assemble(KnowledgeMode.KNOWLEDGE_ASSISTED,
                retrieval(untrusted), request(null, 64));
        String developer = prepared.request().developerInstructions();
        assertThat(developer).contains("〔C999〕", "〔C88〕", "〔C1〕", "〔C77〕");
        assertThat(developer).doesNotContain("Policy [C999]", "[C88]", "[C77]");
        assertThat(prepared.sources()).extracting(KnowledgeContextSource::label).containsExactly("C1");
    }

    @Test
    void emptyFirstHitDoesNotSuppressNextRankedHit() {
        var prepared = assembler.assemble(KnowledgeMode.KNOWLEDGE_ASSISTED,
                retrieval(hit(0, "\n  "), hit(1, "Important evidence")), request(null, 64));
        assertThat(prepared.sources()).hasSize(1);
        assertThat(prepared.sources().getFirst().label()).isEqualTo("C1");
        assertThat(prepared.sources().getFirst().hit().chunkOrdinal()).isEqualTo(1);
    }

    @Test
    void identicalContextHasStableShaAndSourcesAreImmutable() {
        var retrieval = KnowledgeRegressionFixtures.retrieval(hit(0, "Evidence content"));
        var first = assembler.assemble(KnowledgeMode.KNOWLEDGE_ONLY, retrieval, request(null, 64));
        var second = assembler.assemble(KnowledgeMode.KNOWLEDGE_ONLY, retrieval, request(null, 64));
        assertThat(first.contextSha256()).isEqualTo(second.contextSha256()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> first.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void generalModeCannotMaterializeKnowledgeContext() {
        assertThatThrownBy(() -> assembler.assemble(KnowledgeMode.GENERAL,
                retrieval(hit(0, "Evidence")), request(null, 64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
