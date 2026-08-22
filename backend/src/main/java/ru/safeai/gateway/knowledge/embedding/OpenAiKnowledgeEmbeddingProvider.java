package ru.safeai.gateway.knowledge.embedding;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.knowledge.config.KnowledgeEmbeddingProperties;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "safeai.knowledge.embedding.provider",
        havingValue = "openai"
)
public class OpenAiKnowledgeEmbeddingProvider
        implements KnowledgeEmbeddingProvider {

    private final KnowledgeEmbeddingProperties properties;
    private final RestClient client;

    @Autowired
    public OpenAiKnowledgeEmbeddingProvider(
            KnowledgeEmbeddingProperties properties
    ) {
        this(
                properties,
                AiRestClientFactory.create(
                        properties.baseUrl(),
                        properties.connectTimeout(),
                        properties.readTimeout()
                )
        );
    }

    OpenAiKnowledgeEmbeddingProvider(
            KnowledgeEmbeddingProperties properties,
            RestClient client
    ) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public int dimensions() {
        return properties.dimensions();
    }

    @Override
    public String model() {
        return "openai:" + properties.model() + ":d" + dimensions();
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += properties.batchSize()) {
            int end = Math.min(texts.size(), start + properties.batchSize());
            result.addAll(requestBatch(texts.subList(start, end)));
        }
        return List.copyOf(result);
    }

    private List<float[]> requestBatch(List<String> input) {
        List<String> normalized = input.stream()
                .map(this::validateInput)
                .toList();
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", properties.model());
        payload.put("input", normalized);
        payload.put("dimensions", dimensions());
        payload.put("encoding_format", "float");

        try {
            JsonNode body = client.post()
                    .uri("/embeddings")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + properties.apiKey()
                    )
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(body, normalized.size());
        } catch (RestClientException exception) {
            throw new KnowledgeEmbeddingException(
                    "Production embedding provider недоступен",
                    exception
            );
        }
    }

    private List<float[]> parse(JsonNode body, int expected) {
        if (body == null || !body.path("data").isArray()
                || body.path("data").size() != expected) {
            throw new KnowledgeEmbeddingException(
                    "Embedding provider вернул некорректный batch"
            );
        }
        Map<Integer, float[]> indexed = new HashMap<>();
        for (JsonNode item : body.path("data")) {
            int index = item.path("index").asInt(-1);
            JsonNode values = item.path("embedding");
            if (index < 0 || index >= expected
                    || !values.isArray()
                    || values.size() != dimensions()) {
                throw new KnowledgeEmbeddingException(
                        "Embedding provider вернул неверную размерность"
                );
            }
            float[] vector = new float[dimensions()];
            for (int position = 0; position < vector.length; position++) {
                vector[position] = (float) values.get(position).asDouble();
                if (!Float.isFinite(vector[position])) {
                    throw new KnowledgeEmbeddingException(
                            "Embedding содержит нечисловое значение"
                    );
                }
            }
            if (indexed.put(index, vector) != null) {
                throw new KnowledgeEmbeddingException(
                        "Embedding provider продублировал index"
                );
            }
        }
        List<float[]> ordered = new ArrayList<>(expected);
        for (int index = 0; index < expected; index++) {
            float[] vector = indexed.get(index);
            if (vector == null) {
                throw new KnowledgeEmbeddingException(
                        "Embedding provider пропустил index"
                );
            }
            ordered.add(vector);
        }
        return ordered;
    }

    private String validateInput(String value) {
        if (value == null || value.isBlank()
                || value.length() > properties.maxInputChars()) {
            throw new KnowledgeEmbeddingException(
                    "Некорректный текст для embedding"
            );
        }
        return value;
    }
}
