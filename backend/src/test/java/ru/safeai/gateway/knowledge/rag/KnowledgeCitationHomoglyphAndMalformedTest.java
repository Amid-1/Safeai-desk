package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.preparation;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.response;

@Tag("unit")
class KnowledgeCitationHomoglyphAndMalformedTest {

    private final KnowledgeCitationValidator validator = new KnowledgeCitationValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "[Cabc]", "[С1]", "[с1]", "[ C1 ]", "[C 1]", "[C]", "[C999999999999999999999]"
    })
    void knowledgeOnlyAbstainsForMalformedOrHomoglyphCitation(String marker) {
        var completion = validator.validate(
                preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1),
                response("Факт [C1] и подмена " + marker)
        );
        assertThat(completion.response().content()).isEqualTo(KnowledgeCitationValidator.ABSTENTION);
        assertThat(completion.citations()).isEmpty();
        assertThat(completion.citationsValid()).isFalse();
        assertThat(completion.evidenceSufficient()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[Cabc]", "[С1]", "[ C1 ]"})
    void assistedModeCannotRetainAnyAuthoritativeLookingMarkerAfterInvalidity(String marker) {
        var completion = validator.validate(
                preparation(KnowledgeMode.KNOWLEDGE_ASSISTED, 1),
                response("Текст [C1] и фальшивая ссылка " + marker)
        );
        assertThat(completion.response().content()).doesNotContain("[C1]", marker);
        assertThat(completion.citations()).isEmpty();
        assertThat(completion.citationsValid()).isFalse();
        assertThat(completion.evidenceSufficient()).isFalse();
    }
}
