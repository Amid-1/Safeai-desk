package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiPropertiesTest {

    @Test
    void appliesDefaultsForOptionalLimitsAndTimeouts() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                "test-api-key",
                "gpt-4.1",
                null,
                null,
                null,
                null,
                null
        );

        assertThat(properties.baseUrl())
                .isEqualTo("https://api.openai.com/v1");
        assertThat(properties.maxOutputTokens()).isEqualTo(1_024);
        assertThat(properties.maxResponseChars()).isEqualTo(100_000);
        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.effectiveStore()).isFalse();
    }

    @Test
    void preservesValidValues() {
        OpenAiProperties properties = new OpenAiProperties(
                "https://api.openai.com/v1/",
                "api-key",
                "gpt-4.1",
                2_048,
                200_000,
                true,
                Duration.ofSeconds(10),
                Duration.ofSeconds(120)
        );

        assertThat(properties.baseUrl())
                .isEqualTo("https://api.openai.com/v1");
        assertThat(properties.effectiveStore()).isTrue();
    }

    @Test
    void rejectsMissingKeyModelUnsafeHostAndInvalidDurations() {
        assertThatThrownBy(() -> new OpenAiProperties(
                null, " ", "gpt-4.1", null, null, null, null, null
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new OpenAiProperties(
                null, "key", " ", null, null, null, null, null
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new OpenAiProperties(
                "https://attacker.example/v1",
                "key",
                "gpt-4.1",
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new OpenAiProperties(
                null,
                "key",
                "gpt-4.1",
                null,
                null,
                null,
                Duration.ZERO,
                Duration.ofSeconds(60)
        )).isInstanceOf(IllegalStateException.class);
    }
}
