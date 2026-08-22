package ru.safeai.gateway.knowledge.embedding;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
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

    private static final long MAX_RESPONSE_BYTES =
            8L * 1024L * 1024L;

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
                        properties.readTimeout(),
                        MAX_RESPONSE_BYTES
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
        return "openai:"
                + properties.model()
                + ":d"
                + dimensions();
    }

    @Override
    public int preferredBatchSize() {
        return properties.batchSize();
    }

    @Override
    public float[] embed(
            String text
    ) {
        return embedAll(
                List.of(text)
        ).getFirst();
    }

    @Override
    public List<float[]> embedAll(
            List<String> texts
    ) {
        if (texts == null) {
            throw new KnowledgeEmbeddingException(
                    "EMBEDDING_INVALID_INPUT",
                    "Список текстов для embedding не должен быть null",
                    false
            );
        }

        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> result =
                new ArrayList<>(
                        texts.size()
                );

        for (
                int start = 0;
                start < texts.size();
                start += properties.batchSize()
        ) {
            int end =
                    Math.min(
                            texts.size(),
                            start
                                    + properties.batchSize()
                    );

            result.addAll(
                    requestBatch(
                            texts.subList(
                                    start,
                                    end
                            )
                    )
            );
        }

        return List.copyOf(
                result
        );
    }

    private List<float[]> requestBatch(
            List<String> input
    ) {
        List<String> normalized =
                input.stream()
                        .map(this::validateInput)
                        .toList();

        Map<String, Object> payload =
                new HashMap<>();

        payload.put(
                "model",
                properties.model()
        );
        payload.put(
                "input",
                normalized
        );
        payload.put(
                "dimensions",
                dimensions()
        );
        payload.put(
                "encoding_format",
                "float"
        );

        try {
            JsonNode body =
                    client.post()
                            .uri("/embeddings")
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer "
                                            + properties.apiKey()
                            )
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .body(payload)
                            .retrieve()
                            .body(
                                    JsonNode.class
                            );

            return parse(
                    body,
                    normalized.size()
            );
        } catch (RestClientResponseException exception) {
            int status =
                    exception.getStatusCode()
                            .value();

            boolean retryable =
                    status == 408
                            || status == 429
                            || status >= 500;

            throw new KnowledgeEmbeddingException(
                    "EMBEDDING_PROVIDER_HTTP_"
                            + status,
                    "Production embedding provider вернул HTTP "
                            + status,
                    retryable,
                    exception
            );
        } catch (RestClientException exception) {
            throw new KnowledgeEmbeddingException(
                    "EMBEDDING_PROVIDER_UNAVAILABLE",
                    "Production embedding provider недоступен",
                    true,
                    exception
            );
        }
    }

    private List<float[]> parse(
            JsonNode body,
            int expected
    ) {
        if (body == null
                || !body.path("data").isArray()
                || body.path("data").size()
                != expected) {
            throw invalidResponse(
                    "Embedding provider вернул некорректный batch"
            );
        }

        Map<Integer, float[]> indexed =
                new HashMap<>();

        for (JsonNode item : body.path("data")) {
            int index =
                    item.path("index")
                            .asInt(-1);

            JsonNode values =
                    item.path("embedding");

            if (index < 0
                    || index >= expected
                    || !values.isArray()
                    || values.size()
                    != dimensions()) {
                throw invalidResponse(
                        "Embedding provider вернул неверную размерность"
                );
            }

            float[] vector =
                    new float[
                            dimensions()
                            ];

            double squareSum = 0.0;

            for (
                    int position = 0;
                    position < vector.length;
                    position++
            ) {
                double raw =
                        values.get(position)
                                .asDouble();

                if (!Double.isFinite(raw)
                        || raw > Float.MAX_VALUE
                        || raw < -Float.MAX_VALUE) {
                    throw invalidResponse(
                            "Embedding содержит нечисловое значение"
                    );
                }

                vector[position] =
                        (float) raw;

                squareSum +=
                        raw * raw;
            }

            if (squareSum == 0.0
                    || !Double.isFinite(squareSum)) {
                throw invalidResponse(
                        "Embedding provider вернул нулевой или некорректный vector"
                );
            }

            if (indexed.put(
                    index,
                    vector
            ) != null) {
                throw invalidResponse(
                        "Embedding provider продублировал index"
                );
            }
        }

        List<float[]> ordered =
                new ArrayList<>(
                        expected
                );

        for (
                int index = 0;
                index < expected;
                index++
        ) {
            float[] vector =
                    indexed.get(index);

            if (vector == null) {
                throw invalidResponse(
                        "Embedding provider пропустил index"
                );
            }

            ordered.add(vector);
        }

        return List.copyOf(
                ordered
        );
    }

    private String validateInput(
            String value
    ) {
        if (value == null
                || value.isBlank()
                || value.length()
                > properties.maxInputChars()) {
            throw new KnowledgeEmbeddingException(
                    "EMBEDDING_INVALID_INPUT",
                    "Некорректный текст для embedding",
                    false
            );
        }

        return value;
    }

    private static KnowledgeEmbeddingException invalidResponse(
            String message
    ) {
        return new KnowledgeEmbeddingException(
                "EMBEDDING_INVALID_RESPONSE",
                message,
                false
        );
    }
}
