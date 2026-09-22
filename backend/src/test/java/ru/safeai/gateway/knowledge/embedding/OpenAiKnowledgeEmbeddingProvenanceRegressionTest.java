package ru.safeai.gateway.knowledge.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiKnowledgeEmbeddingProvenanceRegressionTest {

    @Test
    void validVectorWithMissingPhysicalModelMustBeRejected() {
        assertInvalidModel(null);
    }

    @Test
    void validVectorWithDifferentPhysicalModelMustBeRejected() {
        assertInvalidModel("text-embedding-3-large");
    }

    @Test
    void exactPhysicalModelIdentityIsAccepted() {
        Setup setup = setup();
        setup.server.expect(requestTo("https://api.openai.com/v1/embeddings"))
                .andRespond(withSuccess(body("text-embedding-3-small"), MediaType.APPLICATION_JSON));
        assertThat(setup.provider.embed("fact")).hasSize(384);
        setup.server.verify();
    }

    private static void assertInvalidModel(String model) {
        Setup setup = setup();
        setup.server.expect(requestTo("https://api.openai.com/v1/embeddings"))
                .andRespond(withSuccess(body(model), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> setup.provider.embed("fact"))
                .isInstanceOf(KnowledgeEmbeddingException.class)
                .satisfies(error -> {
                    KnowledgeEmbeddingException typed = (KnowledgeEmbeddingException) error;
                    assertThat(typed.code()).isEqualTo("EMBEDDING_INVALID_RESPONSE");
                    assertThat(typed.retryable()).isFalse();
                });
        setup.server.verify();
    }

    private static String body(String model) {
        String field = model == null ? "" : "\"model\":\"" + model + "\",";
        return "{" + field + "\"data\":[{\"index\":0,\"embedding\":["
                + String.join(",", Collections.nCopies(384, "0.1")) + "]}]}";
    }

    private static Setup setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties(
                "openai", null, "test-key", "text-embedding-3-small",
                384, 16, 20_000, Duration.ofSeconds(1), Duration.ofSeconds(2));
        return new Setup(new OpenAiKnowledgeEmbeddingProvider(properties, builder.build()), server);
    }

    private record Setup(OpenAiKnowledgeEmbeddingProvider provider,
                         MockRestServiceServer server) { }
}
