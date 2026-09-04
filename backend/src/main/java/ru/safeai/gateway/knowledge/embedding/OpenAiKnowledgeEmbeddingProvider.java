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
import java.util.Objects;

@Component
@ConditionalOnProperty(
        name = "safeai.knowledge.embedding.provider",
        havingValue = "openai"
)
public class OpenAiKnowledgeEmbeddingProvider
        implements KnowledgeEmbeddingProvider {

    private static final long MAX_RESPONSE_BYTES =
            8L * 1024L * 1024L;

    /**
     * Hard aggregate request-body guard.
     *
     * <p>This is intentionally independent from batchSize. A batch containing
     * a small number of very large strings must not be allowed to create an
     * unexpectedly large HTTP request.</p>
     */
    private static final long MAX_BATCH_PAYLOAD_BYTES =
            4L * 1024L * 1024L;

    /**
     * Conservative fixed allowance for JSON field names, dimensions,
     * encoding_format, punctuation and serializer overhead.
     *
     * <p>The variable model and input strings are estimated separately.</p>
     */
    private static final long JSON_BATCH_FIXED_OVERHEAD_BYTES =
            512L;

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
        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties не должен быть null"
                );

        this.client =
                Objects.requireNonNull(
                        client,
                        "client не должен быть null"
                );
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
        /*
         * Validate before List.of(...), because List.of(null) would otherwise
         * leak an unrelated NullPointerException instead of the module's
         * controlled EMBEDDING_INVALID_INPUT error.
         */
        String validated =
                validateInput(
                        text
                );

        return embedAll(
                List.of(
                        validated
                )
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

        List<String> batch =
                new ArrayList<>(
                        properties.batchSize()
                );

        long basePayloadBytes =
                calculateBasePayloadBytes();

        long batchBytes =
                basePayloadBytes;

        for (String raw : texts) {
            String value =
                    validateInput(
                            raw
                    );

            /*
             * +1 is a conservative allowance for the comma separating JSON
             * array entries. For the first element this deliberately
             * overestimates by one byte.
             */
            long valueBytes =
                    safeAdd(
                            estimateJsonStringUpperBoundBytes(
                                    value
                            ),
                            1L
                    );

            /*
             * A single valid input must itself fit inside an otherwise empty
             * provider batch.
             */
            if (safeAdd(
                    basePayloadBytes,
                    valueBytes
            ) > MAX_BATCH_PAYLOAD_BYTES) {
                throw new KnowledgeEmbeddingException(
                        "EMBEDDING_INVALID_INPUT",
                        "Один embedding input превышает максимальный "
                                + "размер provider batch payload",
                        false
                );
            }

            boolean countFull =
                    batch.size()
                            >= properties.batchSize();

            boolean bytesFull =
                    !batch.isEmpty()
                            && safeAdd(
                            batchBytes,
                            valueBytes
                    ) > MAX_BATCH_PAYLOAD_BYTES;

            if (countFull
                    || bytesFull) {

                result.addAll(
                        requestBatch(
                                List.copyOf(
                                        batch
                                )
                        )
                );

                batch.clear();

                batchBytes =
                        basePayloadBytes;
            }

            batch.add(
                    value
            );

            batchBytes =
                    safeAdd(
                            batchBytes,
                            valueBytes
                    );
        }

        if (!batch.isEmpty()) {
            result.addAll(
                    requestBatch(
                            List.copyOf(
                                    batch
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
        if (input == null
                || input.isEmpty()
                || input.size()
                > properties.batchSize()) {
            throw new KnowledgeEmbeddingException(
                    "EMBEDDING_INVALID_INPUT",
                    "Некорректный embedding batch",
                    false
            );
        }

        /*
         * Revalidate at the outbound provider boundary even though embedAll()
         * already validates input. This protects the invariant if requestBatch
         * is refactored or reused later.
         */
        List<String> normalized =
                input.stream()
                        .map(
                                this::validateInput
                        )
                        .toList();

        validateBatchPayloadUpperBound(
                normalized
        );

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
                            .uri(
                                    "/embeddings"
                            )
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer "
                                            + properties.apiKey()
                            )
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .body(
                                    payload
                            )
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

    private void validateBatchPayloadUpperBound(
            List<String> input
    ) {
        long bytes =
                calculateBasePayloadBytes();

        for (String value : input) {
            bytes =
                    safeAdd(
                            bytes,
                            safeAdd(
                                    estimateJsonStringUpperBoundBytes(
                                            value
                                    ),
                                    1L
                            )
                    );

            if (bytes > MAX_BATCH_PAYLOAD_BYTES) {
                throw new KnowledgeEmbeddingException(
                        "EMBEDDING_INVALID_INPUT",
                        "Embedding batch превышает максимальный "
                                + "provider payload",
                        false
                );
            }
        }
    }

    private long calculateBasePayloadBytes() {
        /*
         * Account explicitly for the variable model value instead of assuming
         * that it always fits into the fixed JSON overhead allowance.
         */
        return safeAdd(
                JSON_BATCH_FIXED_OVERHEAD_BYTES,
                estimateJsonStringUpperBoundBytes(
                        properties.model()
                )
        );
    }

    private List<float[]> parse(
            JsonNode body,
            int expected
    ) {
        if (body == null
                || !body.path(
                        "data"
                ).isArray()
                || body.path(
                        "data"
                ).size()
                != expected) {

            throw invalidResponse(
                    "Embedding provider вернул некорректный batch"
            );
        }

        Map<Integer, float[]> indexed =
                new HashMap<>();

        for (JsonNode item :
                body.path(
                        "data"
                )) {

            if (item == null
                    || !item.isObject()) {
                throw invalidResponse(
                        "Embedding provider вернул некорректный элемент batch"
                );
            }

            int index =
                    item.path(
                            "index"
                    ).asInt(
                            -1
                    );

            JsonNode values =
                    item.path(
                            "embedding"
                    );

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

            double squareSum =
                    0.0;

            for (
                    int position = 0;
                    position < vector.length;
                    position++
            ) {
                JsonNode valueNode =
                        values.get(
                                position
                        );

                if (valueNode == null
                        || !valueNode.isNumber()) {
                    throw invalidResponse(
                            "Embedding содержит нечисловое значение"
                    );
                }

                double raw =
                        valueNode.asDouble();

                if (!Double.isFinite(
                        raw
                )
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

                if (!Double.isFinite(
                        squareSum
                )) {
                    throw invalidResponse(
                            "Embedding provider вернул некорректный vector"
                    );
                }
            }

            if (squareSum == 0.0) {
                throw invalidResponse(
                        "Embedding provider вернул нулевой vector"
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
                    indexed.get(
                            index
                    );

            if (vector == null) {
                throw invalidResponse(
                        "Embedding provider пропустил index"
                );
            }

            ordered.add(
                    vector
            );
        }

        return List.copyOf(
                ordered
        );
    }

    /**
     * Conservative upper bound for the UTF-8 byte size of one JSON string.
     *
     * <p>This intentionally assumes that non-ASCII Unicode code points may be
     * emitted using six-byte JSON Unicode escape notation. Supplementary code
     * points may require a UTF-16 surrogate pair and therefore up to twelve
     * ASCII bytes. This is more conservative than Jackson's normal UTF-8
     * output, but makes the request-size safety bound independent from
     * serializer escaping configuration.</p>
     */
    private static long estimateJsonStringUpperBoundBytes(
            String value
    ) {
        if (value == null) {
            return 4L; // JSON null
        }

        long bytes =
                2L; // opening and closing quotes

        for (
                int offset = 0;
                offset < value.length();
        ) {
            int codePoint =
                    value.codePointAt(
                            offset
                    );

            if (codePoint == '"'
                    || codePoint == '\\') {

                bytes =
                        safeAdd(
                                bytes,
                                2L
                        );

            } else if (codePoint <= 0x1F) {

                /*
                 * Worst-case JSON control-character escape occupies
                 * six ASCII bytes.
                 */
                bytes =
                        safeAdd(
                                bytes,
                                6L
                        );

            } else if (codePoint <= 0x7F) {

                bytes =
                        safeAdd(
                                bytes,
                                1L
                        );

            } else if (codePoint <= 0xFFFF) {

                /*
                 * Conservative serializer-independent upper bound:
                 * one escaped UTF-16 code unit occupies six ASCII bytes.
                 */
                bytes =
                        safeAdd(
                                bytes,
                                6L
                        );

            } else {

                /*
                 * A supplementary Unicode code point may be represented
                 * as two escaped UTF-16 surrogate code units.
                 *
                 * Two escaped code units occupy up to twelve ASCII bytes.
                 */
                bytes =
                        safeAdd(
                                bytes,
                                12L
                        );
            }

            offset +=
                    Character.charCount(
                            codePoint
                    );
        }

        return bytes;
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

    private static long safeAdd(
            long left,
            long right
    ) {
        try {
            return Math.addExact(
                    left,
                    right
            );
        } catch (ArithmeticException exception) {
            throw new KnowledgeEmbeddingException(
                    "EMBEDDING_INVALID_INPUT",
                    "Размер embedding payload превысил допустимый диапазон",
                    false,
                    exception
            );
        }
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