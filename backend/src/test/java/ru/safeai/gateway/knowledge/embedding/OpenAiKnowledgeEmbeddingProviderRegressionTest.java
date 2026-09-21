package ru.safeai.gateway.knowledge.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@Tag("contract")
class OpenAiKnowledgeEmbeddingProviderRegressionTest {
    private static final String URL = "https://api.openai.com/v1/embeddings";

    @Test
    void emptyBatchReturnsEmptyWithoutOutboundCall() {
        var setup = setup();
        assertThat(setup.provider().embedAll(List.of())).isEmpty();
        setup.server().verify();
    }

    @Test
    void oversizedAndNullInputsAreRejectedBeforeAnyProviderI0() {
        var setup = setup();
        assertInvalid(() -> setup.provider().embed(null));
        assertInvalid(() -> setup.provider().embed(" "));
        assertInvalid(() -> setup.provider().embedAll(Arrays.asList("valid", null)));
        assertInvalid(() -> setup.provider().embed("a".repeat(100_001)));
        setup.server().verify();
    }

    @Test
    void splitsBatchByConfiguredCountAndPreservesPhysicalResultOrder() {
        var setup = setup();
        setup.server().expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-for-test"))
                .andRespond(withSuccess(batch(2, 0.25f), MediaType.APPLICATION_JSON));
        setup.server().expect(once(), requestTo(URL))
                .andRespond(withSuccess(batch(1, 0.75f), MediaType.APPLICATION_JSON));
        var vectors = setup.provider().embedAll(List.of("alpha", "beta", "gamma"));
        assertThat(vectors).hasSize(3);
        assertThat(vectors).extracting(vector -> vector[0]).containsExactly(.25f, .25f, .75f);
        assertThat(vectors).allSatisfy(vector -> assertThat(vector).hasSize(384));
        setup.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"data\":[]}",
            "{\"data\":[{\"index\":0,\"embedding\":[1]}]}",
            "{\"data\":[{\"index\":2,\"embedding\":[1]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":null}]}",
            "{}"
    })
    void malformedProviderResponseFailsClosed(String body) {
        var setup = setup();
        setup.server().expect(once(), requestTo(URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> setup.provider().embed("alpha"))
                .isInstanceOf(KnowledgeEmbeddingException.class)
                .satisfies(error -> {
                    var exception = (KnowledgeEmbeddingException) error;
                    assertThat(exception.code()).isEqualTo("EMBEDDING_INVALID_RESPONSE");
                    assertThat(exception.retryable()).isFalse();
                });
        setup.server().verify();
    }

    @Test
    void duplicateProviderIndexIsRejectedRatherThanOverwritten() {
        var setup = setup();
        String body = "{\"data\":[{\"index\":0,\"embedding\":" + vector(.25f)
                + "},{\"index\":0,\"embedding\":" + vector(.75f) + "}]}";
        setup.server().expect(once(), requestTo(URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> setup.provider().embedAll(List.of("alpha", "beta")))
                .isInstanceOf(KnowledgeEmbeddingException.class)
                .extracting("code").isEqualTo("EMBEDDING_INVALID_RESPONSE");
        setup.server().verify();
    }

    @Test
    void zeroVectorAndNonNumericComponentCannotBePersisted() {
        var zero = setup();
        zero.server().expect(once(), requestTo(URL))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":"
                        + vector(0f) + "}]}", MediaType.APPLICATION_JSON));
        assertInvalidResponse(() -> zero.provider().embed("alpha"));
        zero.server().verify();
        var nonNumeric = setup();
        String invalid = "[\"not-a-number\","
                + String.join(",", java.util.Collections.nCopies(383, "1")) + "]";
        nonNumeric.server().expect(once(), requestTo(URL))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":"
                        + invalid + "}]}", MediaType.APPLICATION_JSON));
        assertInvalidResponse(() -> nonNumeric.provider().embed("alpha"));
        nonNumeric.server().verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 408, 429, 500, 503})
    void providerHttpFailuresHaveStableRetryability(int status) {
        var setup = setup();
        setup.server().expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThatThrownBy(() -> setup.provider().embed("alpha"))
                .isInstanceOf(KnowledgeEmbeddingException.class)
                .satisfies(error -> {
                    var exception = (KnowledgeEmbeddingException) error;
                    assertThat(exception.code()).isEqualTo("EMBEDDING_PROVIDER_HTTP_" + status);
                    assertThat(exception.retryable()).isEqualTo(status == 408 || status == 429 || status >= 500);
                    assertThat(exception.getMessage()).doesNotContain("secret-for-test");
                });
        setup.server().verify();
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callback) {
        assertThatThrownBy(callback).isInstanceOf(KnowledgeEmbeddingException.class)
                .extracting("code").isEqualTo("EMBEDDING_INVALID_INPUT");
    }

    private static void assertInvalidResponse(org.assertj.core.api.ThrowableAssert.ThrowingCallable callback) {
        assertThatThrownBy(callback).isInstanceOf(KnowledgeEmbeddingException.class)
                .extracting("code").isEqualTo("EMBEDDING_INVALID_RESPONSE");
    }

    private static Setup setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = new KnowledgeEmbeddingProperties("openai", null, "secret-for-test",
                "text-embedding-3-small", 384, 2, 100_000,
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        return new Setup(new OpenAiKnowledgeEmbeddingProvider(properties, builder.build()), server);
    }

    private static String batch(int size, float component) {
        return "{\"data\":[" + java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> "{\"index\":" + index + ",\"embedding\":" + vector(component) + "}")
                .collect(Collectors.joining(",")) + "]}";
    }

    private static String vector(float component) {
        return "[" + String.join(",", java.util.Collections.nCopies(384, Float.toString(component))) + "]";
    }

    private record Setup(OpenAiKnowledgeEmbeddingProvider provider, MockRestServiceServer server) { }
}
