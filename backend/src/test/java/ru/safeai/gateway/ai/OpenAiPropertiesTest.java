package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiPropertiesTest {

    @Test
    void constructor_shouldApplyDefaultValues() {
        OpenAiProperties properties = new OpenAiProperties(
                null,
                "api-key",
                null,
                null,
                null,
                null
        );

        assertThat(properties.baseUrl())
                .isEqualTo("https://api.openai.com/v1");

        assertThat(properties.model())
                .isEqualTo("gpt-4.1");

        assertThat(properties.maxOutputTokens())
                .isEqualTo(1024);

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
                Duration.ofSeconds(10),
                Duration.ofSeconds(120)
        );

        assertThat(properties.baseUrl())
                .isEqualTo("https://custom-openai.example");

        assertThat(properties.model())
                .isEqualTo("gpt-4o");

        assertThat(properties.maxOutputTokens())
                .isEqualTo(2048);

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
                null
        );

        assertThat(properties.maxOutputTokens())
                .isEqualTo(1024);
    }
}