package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AnthropicPropertiesTest {

    private static final String DEFAULT_BASE_URL =
            "https://api.anthropic.com/v1";

    private static final String DEFAULT_API_VERSION =
            "2023-06-01";

    private static final String TEST_API_KEY =
            "test-api-key";

    private static final String TEST_MODEL =
            "claude-sonnet";

    @Test
    void appliesSynchronousProductionDefaults() {
        AnthropicProperties properties =
                new AnthropicProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.baseUrl())
                .isEqualTo(DEFAULT_BASE_URL);

        assertThat(properties.apiKey())
                .isEqualTo(TEST_API_KEY);

        assertThat(properties.model())
                .isEqualTo(TEST_MODEL);

        assertThat(properties.version())
                .isEqualTo(DEFAULT_API_VERSION);

        assertThat(properties.maxInputTokens())
                .isEqualTo(64_000);

        assertThat(properties.maxTokens())
                .isEqualTo(2_048);

        assertThat(properties.maxResponseChars())
                .isEqualTo(100_000);

        assertThat(properties.maxResponseBodyBytes())
                .isEqualTo(2L * 1024L * 1024L);

        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(5));

        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void appliesDefaultsForBlankBaseUrlAndVersion() {
        AnthropicProperties properties =
                new AnthropicProperties(
                        "   ",
                        TEST_API_KEY,
                        TEST_MODEL,
                        "   ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.baseUrl())
                .isEqualTo(DEFAULT_BASE_URL);

        assertThat(properties.version())
                .isEqualTo(DEFAULT_API_VERSION);
    }

    @Test
    void appliesExplicitValues() {
        AnthropicProperties properties =
                new AnthropicProperties(
                        DEFAULT_BASE_URL,
                        TEST_API_KEY,
                        TEST_MODEL,
                        DEFAULT_API_VERSION,
                        100_000,
                        4_096,
                        250_000,
                        4L * 1024L * 1024L,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(45)
                );

        assertThat(properties.baseUrl())
                .isEqualTo(DEFAULT_BASE_URL);

        assertThat(properties.apiKey())
                .isEqualTo(TEST_API_KEY);

        assertThat(properties.model())
                .isEqualTo(TEST_MODEL);

        assertThat(properties.version())
                .isEqualTo(DEFAULT_API_VERSION);

        assertThat(properties.maxInputTokens())
                .isEqualTo(100_000);

        assertThat(properties.maxTokens())
                .isEqualTo(4_096);

        assertThat(properties.maxResponseChars())
                .isEqualTo(250_000);

        assertThat(properties.maxResponseBodyBytes())
                .isEqualTo(4L * 1024L * 1024L);

        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(3));

        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void toStringDoesNotExposeApiKey() {
        String secret = "super-secret-key";

        AnthropicProperties properties =
                new AnthropicProperties(
                        null,
                        secret,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThat(properties.toString())
                .doesNotContain(secret);
    }

    @Test
    void rejectsCustomPathQueryPortAndUnsafeHost() {
        assertInvalidBaseUrl(
                "https://api.anthropic.com/v2"
        );

        assertInvalidBaseUrl(
                "https://api.anthropic.com/v1?debug=true"
        );

        assertInvalidBaseUrl(
                "https://api.anthropic.com:8443/v1"
        );

        assertInvalidBaseUrl(
                "https://attacker.example/v1"
        );
    }

    @Test
    void rejectsExcessiveNonStreamingOutput() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        null,
                        8_193,
                        null,
                        null,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(60)
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTooSmallInputTokenLimit() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        1_023,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsExcessiveInputTokenLimit() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        1_000_001,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTooSmallResponseBodyLimit() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        null,
                        64L * 1024L - 1L,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsExcessiveResponseBodyLimit() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        null,
                        16L * 1024L * 1024L + 1L,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingApiKey() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        null,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankApiKey() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        "   ",
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingModel() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        TEST_API_KEY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankModel() {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        null,
                        TEST_API_KEY,
                        "   ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertInvalidBaseUrl(
            String baseUrl
    ) {
        assertThatThrownBy(
                () -> new AnthropicProperties(
                        baseUrl,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }
}