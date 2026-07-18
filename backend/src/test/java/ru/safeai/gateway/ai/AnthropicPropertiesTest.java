package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicPropertiesTest {

    @Test
    void appliesDefaults() {
        AnthropicProperties properties = new AnthropicProperties(
                null,
                "test-api-key",
                "claude-opus-4-8",
                null,
                null,
                null,
                null,
                null
        );

        assertThat(properties.baseUrl())
                .isEqualTo("https://api.anthropic.com/v1");
        assertThat(properties.version()).isEqualTo("2023-06-01");
        assertThat(properties.maxTokens()).isEqualTo(1_024);
        assertThat(properties.maxResponseChars()).isEqualTo(100_000);
        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsMissingKeyModelUnsafeHostAndInvalidLimits() {
        assertThatThrownBy(() -> new AnthropicProperties(
                null, " ", "claude", null, null, null, null, null
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new AnthropicProperties(
                null, "key", " ", null, null, null, null, null
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new AnthropicProperties(
                "https://attacker.example/v1",
                "key",
                "claude",
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new AnthropicProperties(
                null,
                "key",
                "claude",
                null,
                0,
                null,
                null,
                null
        )).isInstanceOf(IllegalStateException.class);
    }
}
