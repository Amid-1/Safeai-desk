package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class OpenAiPropertiesTest {

    private static final String DEFAULT_BASE_URL =
            "https://api.openai.com/v1";

    private static final String TEST_API_KEY =
            "test-api-key";

    private static final String TEST_MODEL =
            "gpt-4.1";

    @Test
    void appliesSynchronousProductionDefaults() {
        OpenAiProperties properties =
                new OpenAiProperties(
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

        assertThat(properties.maxInputTokens())
                .isEqualTo(64_000);

        assertThat(properties.maxOutputTokens())
                .isEqualTo(2_048);

        assertThat(properties.maxResponseChars())
                .isEqualTo(100_000);

        assertThat(properties.maxResponseBodyBytes())
                .isEqualTo(2L * 1024L * 1024L);

        assertThat(properties.store())
                .isFalse();

        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(5));

        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void appliesDefaultBaseUrlForBlankValue() {
        OpenAiProperties properties =
                new OpenAiProperties(
                        "   ",
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
    }

    @Test
    void appliesExplicitValues() {
        OpenAiProperties properties =
                new OpenAiProperties(
                        DEFAULT_BASE_URL,
                        TEST_API_KEY,
                        TEST_MODEL,
                        100_000,
                        4_096,
                        250_000,
                        4L * 1024L * 1024L,
                        true,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(45)
                );

        assertThat(properties.baseUrl())
                .isEqualTo(DEFAULT_BASE_URL);

        assertThat(properties.apiKey())
                .isEqualTo(TEST_API_KEY);

        assertThat(properties.model())
                .isEqualTo(TEST_MODEL);

        assertThat(properties.maxInputTokens())
                .isEqualTo(100_000);

        assertThat(properties.maxOutputTokens())
                .isEqualTo(4_096);

        assertThat(properties.maxResponseChars())
                .isEqualTo(250_000);

        assertThat(properties.maxResponseBodyBytes())
                .isEqualTo(4L * 1024L * 1024L);

        assertThat(properties.store())
                .isTrue();

        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(3));

        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(45));
    }

    @ParameterizedTest
    @MethodSource("storeValues")
    void calculatesEffectiveStore(
            Boolean configuredStore,
            boolean expectedStore
    ) {
        OpenAiProperties properties =
                new OpenAiProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        null,
                        configuredStore,
                        null,
                        null
                );

        assertThat(properties.effectiveStore())
                .isEqualTo(expectedStore);
    }

    @Test
    void toStringDoesNotExposeApiKey() {
        String secret = "super-secret-key";

        OpenAiProperties properties =
                new OpenAiProperties(
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
                "https://api.openai.com/v2"
        );

        assertInvalidBaseUrl(
                "https://api.openai.com/v1?debug=true"
        );

        assertInvalidBaseUrl(
                "https://api.openai.com:8443/v1"
        );

        assertInvalidBaseUrl(
                "https://attacker.example/v1"
        );
    }

    @Test
    void rejectsExcessiveNonStreamingOutput() {
        assertThatThrownBy(
                () -> new OpenAiProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        8_193,
                        null,
                        null,
                        null,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(60)
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsExcessiveReadTimeout() {
        assertThatThrownBy(
                () -> new OpenAiProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        2_048,
                        null,
                        null,
                        null,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(91)
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTooSmallInputTokenLimit() {
        assertThatThrownBy(
                () -> new OpenAiProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        1_023,
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
    void rejectsExcessiveInputTokenLimit() {
        assertThatThrownBy(
                () -> new OpenAiProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        1_000_001,
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
    void rejectsTooSmallResponseBodyLimit() {
        assertThatThrownBy(
                () -> new OpenAiProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        64L * 1024L - 1L,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsExcessiveResponseBodyLimit() {
        assertThatThrownBy(
                () -> new OpenAiProperties(
                        null,
                        TEST_API_KEY,
                        TEST_MODEL,
                        null,
                        null,
                        null,
                        16L * 1024L * 1024L + 1L,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingApiKey() {
        assertThatThrownBy(
                () -> new OpenAiProperties(
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
                () -> new OpenAiProperties(
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
                () -> new OpenAiProperties(
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
                () -> new OpenAiProperties(
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

    private static Stream<Arguments> storeValues() {
        return Stream.of(
                Arguments.of(null, false),
                Arguments.of(false, false),
                Arguments.of(true, true)
        );
    }

    private static void assertInvalidBaseUrl(
            String baseUrl
    ) {
        assertThatThrownBy(
                () -> new OpenAiProperties(
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