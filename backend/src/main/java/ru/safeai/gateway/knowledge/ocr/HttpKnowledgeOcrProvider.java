package ru.safeai.gateway.knowledge.ocr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.ai.provider.ProviderCredentialEvidence;
import ru.safeai.gateway.ai.provider.ProviderDataAssuranceProperties;
import ru.safeai.gateway.ai.provider.ProviderDataEvidence;
import ru.safeai.gateway.knowledge.config.KnowledgeOcrProperties;
import ru.safeai.gateway.knowledge.config.KnowledgeProviderDataGuard;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Clock;

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
    private final ProviderDataEvidence evidence;
    private final Clock clock;

    @Autowired
    public HttpKnowledgeOcrProvider(
            KnowledgeOcrProperties properties,
            ProviderDataAssuranceProperties assurances,
            Clock clock
    ) {
        this(properties, AiRestClientFactory.create(properties.endpoint(), properties.connectTimeout(),
                properties.readTimeout(), properties.maxResponseBytes()), assurances.ocr(), clock);
    }

    HttpKnowledgeOcrProvider(KnowledgeOcrProperties properties, RestClient client,
            ProviderDataEvidence evidence, Clock clock) {
        this.properties = properties;
        this.client = java.util.Objects.requireNonNull(client);
        this.evidence = evidence;
        this.clock = java.util.Objects.requireNonNull(clock);
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

        if (pdf.length > properties.maxInputBytes()) {
            throw new KnowledgeIngestionException(
                    "OCR_INPUT_TOO_LARGE",
                    "PDF превышает отдельный безопасный лимит OCR request",
                    false
            );
        }

        if (!KnowledgeProviderDataGuard.permits(evidence, clock, properties.endpoint(),
                KnowledgeProviderDataGuard.ocrBinding(properties))) {
            throw new KnowledgeIngestionException("OCR_DATA_ASSURANCE_DENIED",
                    "External OCR requires current no-training and zero-retention assurance", false);
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

            if (ProviderCredentialEvidence.reflected(body, null, properties.apiKey())) {
                throw new KnowledgeIngestionException("OCR_PROVIDER_PROTOCOL_ERROR",
                        "OCR response contains forbidden credential material", false);
            }
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
                    retryable
            );
        } catch (RestClientException ignored) {
            throw new KnowledgeIngestionException(
                    "OCR_PROVIDER_UNAVAILABLE",
                    "OCR provider временно недоступен",
                    true
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
