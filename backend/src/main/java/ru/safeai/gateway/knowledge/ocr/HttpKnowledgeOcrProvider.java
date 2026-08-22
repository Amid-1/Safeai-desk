package ru.safeai.gateway.knowledge.ocr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.knowledge.config.KnowledgeOcrProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(
        name = "safeai.knowledge.ocr.provider",
        havingValue = "http"
)
public class HttpKnowledgeOcrProvider
        implements KnowledgeOcrProvider {

    private static final int MAX_MODEL_VERSION_LENGTH = 80;

    private final KnowledgeOcrProperties properties;
    private final RestClient client;

    public HttpKnowledgeOcrProvider(
            KnowledgeOcrProperties properties
    ) {
        this.properties = properties;
        this.client =
                AiRestClientFactory.create(
                        properties.endpoint(),
                        properties.connectTimeout(),
                        properties.readTimeout(),
                        properties.maxResponseBytes()
                );
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public OcrDocument extractPdf(
            byte[] pdf
    ) {
        if (pdf == null
                || pdf.length == 0) {
            throw new KnowledgeIngestionException(
                    "OCR_INVALID_INPUT",
                    "OCR получил пустой PDF",
                    false
            );
        }

        try {
            JsonNode body =
                    client.post()
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer "
                                            + properties.apiKey()
                            )
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .body(
                                    Map.of(
                                            "mediaType",
                                            "application/pdf",
                                            "contentBase64",
                                            Base64.getEncoder()
                                                    .encodeToString(
                                                            pdf
                                                    )
                                    )
                            )
                            .retrieve()
                            .body(
                                    JsonNode.class
                            );

            return parse(
                    body
            );
        } catch (RestClientResponseException exception) {
            int status =
                    exception.getStatusCode()
                            .value();

            boolean retryable =
                    status == 408
                            || status == 429
                            || status >= 500;

            throw new KnowledgeIngestionException(
                    "OCR_PROVIDER_HTTP_"
                            + status,
                    "OCR provider вернул HTTP "
                            + status,
                    retryable,
                    exception
            );
        } catch (RestClientException exception) {
            throw new KnowledgeIngestionException(
                    "OCR_PROVIDER_UNAVAILABLE",
                    "OCR provider временно недоступен",
                    true,
                    exception
            );
        }
    }

    private OcrDocument parse(
            JsonNode body
    ) {
        if (body == null) {
            throw invalidResponse();
        }

        JsonNode pagesNode =
                body.path(
                        "pages"
                );

        if (!pagesNode.isArray()
                || pagesNode.isEmpty()) {
            throw invalidResponse();
        }

        String modelVersion =
                stringValue(
                        body,
                        "modelVersion"
                ).strip();

        if (!validModelVersion(
                modelVersion
        )) {
            throw invalidResponse();
        }

        List<OcrPage> pages =
                new ArrayList<>(
                        pagesNode.size()
                );

        Set<Integer> seen =
                new HashSet<>();

        for (JsonNode page : pagesNode) {
            int number =
                    pageNumber(
                            page
                    );

            String text =
                    stringValue(
                            page,
                            "text"
                    );

            if (number < 1
                    || !seen.add(
                    number
            )) {
                throw invalidResponse();
            }

            pages.add(
                    new OcrPage(
                            number,
                            text
                    )
            );
        }

        pages.sort(
                Comparator.comparingInt(
                        OcrPage::pageNumber
                )
        );

        return new OcrDocument(
                "http-ocr:"
                        + modelVersion,
                pages
        );
    }

    private static String stringValue(
            JsonNode parent,
            String field
    ) {
        return parent.path(
                field
        ).stringValue(
                ""
        );
    }

    private static int pageNumber(
            JsonNode page
    ) {
        JsonNode value =
                page.path(
                        "page"
                );

        if (!value.isIntegralNumber()
                || !value.canConvertToInt()) {
            return -1;
        }

        return value.intValue();
    }

    private static boolean validModelVersion(
            String value
    ) {
        if (value.isBlank()
                || value.length()
                > MAX_MODEL_VERSION_LENGTH) {
            return false;
        }

        return value.codePoints()
                .noneMatch(
                        Character::isISOControl
                );
    }

    private static KnowledgeIngestionException invalidResponse() {
        return new KnowledgeIngestionException(
                "OCR_INVALID_RESPONSE",
                "OCR provider вернул некорректный ответ",
                false
        );
    }
}
