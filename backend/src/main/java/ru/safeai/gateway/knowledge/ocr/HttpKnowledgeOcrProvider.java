package ru.safeai.gateway.knowledge.ocr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.knowledge.config.KnowledgeOcrProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(
        name = "safeai.knowledge.ocr.provider",
        havingValue = "http"
)
public class HttpKnowledgeOcrProvider implements KnowledgeOcrProvider {

    private final KnowledgeOcrProperties properties;
    private final RestClient client;

    public HttpKnowledgeOcrProvider(KnowledgeOcrProperties properties) {
        this.properties = properties;
        this.client = AiRestClientFactory.create(
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
    public OcrDocument extractPdf(byte[] pdf) {
        try {
            JsonNode body = client.post()
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + properties.apiKey()
                    )
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "mediaType", "application/pdf",
                            "contentBase64", Base64.getEncoder().encodeToString(pdf)
                    ))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(body);
        } catch (RestClientException exception) {
            throw new KnowledgeIngestionException(
                    "OCR_PROVIDER_UNAVAILABLE",
                    "OCR provider временно недоступен",
                    true,
                    exception
            );
        }
    }

    private OcrDocument parse(JsonNode body) {
        if (body == null || !body.path("pages").isArray()) {
            throw invalidResponse();
        }
        String modelVersion = body.path("modelVersion").asText("").strip();
        if (modelVersion.isBlank() || modelVersion.length() > 80) {
            throw invalidResponse();
        }
        List<OcrPage> pages = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode page : body.path("pages")) {
            int number = page.path("page").asInt(-1);
            String text = page.path("text").asText("");
            if (number < 1 || !seen.add(number)) {
                throw invalidResponse();
            }
            pages.add(new OcrPage(number, text));
        }
        pages.sort(java.util.Comparator.comparingInt(OcrPage::pageNumber));
        return new OcrDocument("http-ocr:" + modelVersion, pages);
    }

    private static KnowledgeIngestionException invalidResponse() {
        return new KnowledgeIngestionException(
                "OCR_INVALID_RESPONSE",
                "OCR provider вернул некорректный ответ",
                false
        );
    }
}
