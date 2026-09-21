package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.*;

@Tag("unit")
class KnowledgeCitationValidatorRegressionTest {
    private final KnowledgeCitationValidator validator = new KnowledgeCitationValidator();

    @ParameterizedTest
    @ValueSource(strings = {"[C0]", "[C01]", "[c001]", "[C999]", "[C1000]", "[C999999999999999999999]"})
    void knowledgeOnlyAbstainsOnUnapprovedOrMalformedNumericMarkers(String marker) {
        RagCompletion result = validator.validate(preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1),
                response("Claim " + marker));
        assertThat(result.response().content()).isEqualTo(KnowledgeCitationValidator.ABSTENTION);
        assertThat(result.citations()).isEmpty();
        assertThat(result.citationsValid()).isFalse();
        assertThat(result.evidenceSufficient()).isFalse();
    }

    @Test
    void mixingValidAndInvalidCitationMustNeverPersistPartialEvidence() {
        RagCompletion result = validator.validate(preparation(KnowledgeMode.KNOWLEDGE_ASSISTED, 2),
                response("First [C1], then [C999], finally [C2]."));
        assertThat(result.citations()).isEmpty();
        assertThat(result.citationsValid()).isFalse();
        assertThat(result.evidenceSufficient()).isFalse();
        assertThat(result.response().content()).doesNotContain("[C1]", "[C2]", "[C999]");
    }

    @Test
    void repeatedAndLowercaseCitationsAreCanonicalizedAndDeduplicatedByFirstAppearance() {
        RagCompletion result = validator.validate(preparation(KnowledgeMode.KNOWLEDGE_ONLY, 2),
                response("[c2] [C1] [C2] [c1]"));
        assertThat(result.response().content()).isEqualTo("[C2] [C1] [C2] [C1]");
        assertThat(result.citations()).extracting(RagCitation::label).containsExactly("C2", "C1");
        assertThat(result.citationsValid()).isTrue();
        assertThat(result.evidenceSufficient()).isTrue();
        assertThat(result.citations().getFirst().chunkId())
                .isEqualTo(preparation(KnowledgeMode.KNOWLEDGE_ONLY, 2).sources().get(1).hit().chunkId());
    }

    @Test
    void noCitationsInKnowledgeOnlyMustAbstainWithoutFalselyInvalidatingSyntax() {
        RagCompletion result = validator.validate(preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1),
                response("An uncited assertion"));
        assertThat(result.response().content()).isEqualTo(KnowledgeCitationValidator.ABSTENTION);
        assertThat(result.citationsValid()).isTrue();
        assertThat(result.evidenceSufficient()).isFalse();
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void noSourcesInKnowledgeOnlyMustAbstainEvenWithAppLookingCitation() {
        RagCompletion result = validator.validate(preparation(KnowledgeMode.KNOWLEDGE_ONLY, 0),
                response("Unsupported [C1]"));
        assertThat(result.response().content()).isEqualTo(KnowledgeCitationValidator.ABSTENTION);
        assertThat(result.citationsValid()).isFalse();
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void citationRewritingMustPreserveCompletePhysicalUsageAndPricingProvenance() {
        var original = ru.safeai.gateway.ai.dto.AiChatResponse.fromProvider(
                "Fact [c1]", "requested-model", "resolved-model", "provider-message-id",
                "provider-request-id", ru.safeai.gateway.ai.metadata.AiResponseStatus.COMPLETED,
                "completed", new ru.safeai.gateway.ai.metadata.AiTokenUsage(
                        1000, 600, 200, 100, true, true),
                ru.safeai.gateway.ai.pricing.PricingResult.unpriced(NOW));
        var completion = validator.validate(preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1), original);
        var rewritten = completion.response();
        assertThat(rewritten.content()).isEqualTo("Fact [C1]");
        assertThat(rewritten.inputTokens()).isEqualTo(1000);
        assertThat(rewritten.cachedInputTokens()).isEqualTo(600);
        assertThat(rewritten.cacheWriteInputTokens()).isEqualTo(200);
        assertThat(rewritten.specializedBillingDimensionsPresent()).isTrue();
        assertThat(rewritten.specializedBillingDimensionsValid()).isTrue();
        assertThat(rewritten.outputTokens()).isEqualTo(100);
        assertThat(rewritten.requestedModel()).isEqualTo("requested-model");
        assertThat(rewritten.model()).isEqualTo("resolved-model");
        assertThat(rewritten.providerRequestId()).isEqualTo("provider-request-id");
        assertThat(rewritten.pricingStatus()).isEqualTo(original.pricingStatus());
        assertThat(rewritten.pricingCalculatedAt()).isEqualTo(original.pricingCalculatedAt());
    }

    @Test
    void generalModeMustNotInterpretUserContentAsKnowledgeEvidence() {
        var request = request(4096L, 100);
        RagCompletion result = validator.validate(RagPreparation.general(request),
                response("Example [C999] is just text."));
        assertThat(result.response().content()).isEqualTo("Example [C999] is just text.");
        assertThat(result.citationsValid()).isTrue();
        assertThat(result.evidenceSufficient()).isTrue();
        assertThat(result.citations()).isEmpty();
    }
}
