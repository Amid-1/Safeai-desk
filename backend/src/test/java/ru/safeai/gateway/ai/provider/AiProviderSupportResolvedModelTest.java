package ru.safeai.gateway.ai.provider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AiProviderSupportResolvedModelTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    @Test
    void validResolvedModelIsPreservedExactly() {
        assertThat(
                AiProviderSupport.resolvedModel(
                        JSON.readTree(
                                "{\"model\":\"gpt-5-2026-09\"}"
                        )
                )
        ).isEqualTo("gpt-5-2026-09");
    }

    @Test
    void missingBlankOrNonStringModelNeverFallsBack() {
        for (String json : new String[]{
                "{}",
                "{\"model\":null}",
                "{\"model\":\"\"}",
                "{\"model\":123}",
                "{\"model\":true}"
        }) {
            assertThatThrownBy(
                    () -> AiProviderSupport.resolvedModel(
                            JSON.readTree(json)
                    )
            )
                    .as(json)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void oversizedOrControlCharacterModelCannotBeRecordedAsPhysicalProvenance() {
        for (String model : new String[]{
                "g".repeat(101),
                "gpt\nunsafe"
        }) {
            String json =
                    "{\"model\":\""
                            + model.replace("\n", "\\n")
                            + "\"}";

            assertThatThrownBy(
                    () -> AiProviderSupport.resolvedModel(
                            JSON.readTree(json)
                    )
            ).isInstanceOf(IllegalStateException.class);
        }
    }
}