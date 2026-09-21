package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.*;

@Tag("unit")
class RagPreparationContractRegressionTest {
    @Test
    void generalMustNotCarryKnowledgeScope() {
        assertThatThrownBy(() -> new RagPreparation(KnowledgeMode.GENERAL, KB_ID, null, null,
                null, List.of(), request(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knowledgeEnabledRequiresRunIdentityAndValidContentHash() {
        assertThatThrownBy(() -> new RagPreparation(KnowledgeMode.KNOWLEDGE_ONLY,
                KB_ID, null, "model", "a".repeat(64), List.of(), request(null, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RagPreparation(KnowledgeMode.KNOWLEDGE_ONLY,
                KB_ID, RUN_ID, "model", "not-a-sha", List.of(), request(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagPreparation(KnowledgeMode.KNOWLEDGE_ASSISTED,
                KB_ID, RUN_ID, " ", "a".repeat(64), List.of(), request(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparationCopiesSourcesAndRejectsLaterMutation() {
        var sources = new ArrayList<KnowledgeContextSource>();
        sources.add(new KnowledgeContextSource("C1", hit(0, "Evidence")));
        var prepared = new RagPreparation(KnowledgeMode.KNOWLEDGE_ONLY, KB_ID, RUN_ID,
                "model", "a".repeat(64), sources, request(null, null));
        sources.clear();
        assertThat(prepared.sources()).hasSize(1);
        assertThatThrownBy(() -> prepared.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void generalProjectionPreservesRequestIdentityWithoutKnowledgeState() {
        var original = request(1_000L, 64);
        var preparation = RagPreparation.general(original);
        assertThat(preparation.mode()).isEqualTo(KnowledgeMode.GENERAL);
        assertThat(preparation.aiRequest()).isSameAs(original);
        assertThat(preparation.knowledgeBaseId()).isNull();
        assertThat(preparation.retrievalRunId()).isNull();
        assertThat(preparation.contextSha256()).isNull();
    }
}
