package ru.safeai.gateway.knowledge.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiKnowledgeEmbeddingProviderTest {

    @Test
    void parsesBatchByProviderIndexAndEnforces384Dimensions() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.openai.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .build();
        OpenAiKnowledgeEmbeddingProvider provider =
                new OpenAiKnowledgeEmbeddingProvider(
                        properties(),
                        builder.build()
                );
        String first = vectorJson(0.25f);
        String second = vectorJson(0.5f);
        server.expect(once(), requestTo(
                        "https://api.openai.com/v1/embeddings"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString(
                        "\"dimensions\":384"
                )))
                .andRespond(withSuccess(
                        """
                        {"data":[
                          {"index":1,"embedding":%s},
                          {"index":0,"embedding":%s}
                        ]}
                        """.formatted(second, first),
                        MediaType.APPLICATION_JSON
                ));

        List<float[]> result = provider.embedAll(List.of("one", "two"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).hasSize(384);
        assertThat(result.get(0)[0]).isEqualTo(0.25f);
        assertThat(result.get(1)[0]).isEqualTo(0.5f);
        server.verify();
    }

    private static KnowledgeEmbeddingProperties properties() {
        return new KnowledgeEmbeddingProperties(
                "openai",
                "https://api.openai.com/v1",
                "test-secret",
                "text-embedding-3-small",
                384,
                64,
                20_000,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    private static String vectorJson(float value) {
        return "[" + java.util.Collections.nCopies(384, Float.toString(value))
                .stream()
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }
}
