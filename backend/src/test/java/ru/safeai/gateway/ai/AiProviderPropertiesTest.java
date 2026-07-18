package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.AiProviderProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderPropertiesTest {

    @Test
    void defaultsToMockAndNormalizesKnownProvider() {
        assertThat(new AiProviderProperties(null).provider())
                .isEqualTo("mock");
        assertThat(new AiProviderProperties(" OpenAI ").provider())
                .isEqualTo("openai");
    }

    @Test
    void rejectsUnknownProvider() {
        assertThatThrownBy(() ->
                new AiProviderProperties("opneai")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("opneai");
    }
}
