package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiPropertiesTest {

    @Test
    void constructor_shouldApplyDefaultValues() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                "test-api-key",
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(properties.baseUrl())
                .isEqualTo("https://api.openai.com/v1");

        assertThat(properties.apiKey())
                .isEqualTo("test-api-key");

        assertThat(properties.model())
                .isEqualTo("gpt-4.1");

        assertThat(properties.maxOutputTokens())
                .isEqualTo(1024);

        assertThat(properties.maxResponseChars())
                .isEqualTo(100_000);

        assertThat(properties.store())
                .isNull();

        assertThat(properties.effectiveStore())
                .isFalse();

        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(5));

        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void constructor_shouldPreserveProvidedValues() {
        OpenAiProperties properties = new OpenAiProperties(
                "https://custom-openai.example",
                "api-key",
                "gpt-4o",
                2048,
                200_000,
                true,
                Duration.ofSeconds(10),
                Duration.ofSeconds(120)
        );

        assertThat(properties.baseUrl())
                .isEqualTo("https://custom-openai.example");

        assertThat(properties.apiKey())
                .isEqualTo("api-key");

        assertThat(properties.model())
                .isEqualTo("gpt-4o");

        assertThat(properties.maxOutputTokens())
                .isEqualTo(2048);

        assertThat(properties.maxResponseChars())
                .isEqualTo(200_000);

        assertThat(properties.store())
                .isTrue();

        assertThat(properties.effectiveStore())
                .isTrue();

        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(10));

        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void constructor_shouldNormalizeInvalidMaxOutputTokens() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                "api-key",
                null,
                -1,
                null,
                null,
                null,
                null
        );

        assertThat(properties.maxOutputTokens())
                .isEqualTo(1024);
    }

    @Test
    void constructor_shouldNormalizeInvalidMaxResponseChars() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                "api-key",
                null,
                null,
                -1,
                null,
                null,
                null
        );

        assertThat(properties.maxResponseChars())
                .isEqualTo(100_000);
    }

    @Test
    void constructor_shouldCapTooLargeMaxResponseChars() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                "api-key",
                null,
                null,
                2_000_000,
                null,
                null,
                null
        );

        assertThat(properties.maxResponseChars())
                .isEqualTo(1_000_000);
    }

    @Test
    void effectiveStore_shouldReturnFalseWhenStoreIsFalse() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                "api-key",
                null,
                null,
                null,
                false,
                null,
                null
        );

        assertThat(properties.effectiveStore())
                .isFalse();
    }

    @Test
    void validate_shouldThrowWhenApiKeyIsBlank() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                " ",
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OPENAI_API_KEY не задан");
    }

    @Test
    void validate_shouldNotThrowWhenApiKeyIsPresent() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                "api-key",
                null,
                null,
                null,
                null,
                null,
                null
        );

        properties.validate();
    }
}