package ru.safeai.gateway.knowledge.ocr;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.provider.ProviderDataEvidence;
import ru.safeai.gateway.knowledge.config.KnowledgeOcrProperties;
import ru.safeai.gateway.knowledge.config.KnowledgeProviderDataGuard;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;
import java.time.*;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OcrDataAssuranceTest {
    @Test
    void matchingCurrentEvidenceAllowsDocumentToBeProcessed() {
        var p = new KnowledgeOcrProperties("http", "https://ocr.safeai.test/api", "test-key",
                List.of("ocr.safeai.test"), 20, Duration.ofSeconds(1), Duration.ofSeconds(2),
                16L * 1024 * 1024, 25L * 1024 * 1024);
        var now = Instant.parse("2026-11-17T12:00:00Z");
        var builder = RestClient.builder().baseUrl(p.endpoint());
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(p.endpoint())).andRespond(withSuccess(
                "{\"modelVersion\":\"ocr-v1\",\"pages\":[{\"page\":1,\"text\":\"test\"}]}",
                org.springframework.http.MediaType.APPLICATION_JSON));
        var provider = new HttpKnowledgeOcrProvider(p, builder.build(),
                new ProviderDataEvidence("DOC-TEST", now, now.plusSeconds(60),
                        KnowledgeProviderDataGuard.ocrBinding(p), true, true),
                Clock.fixed(now, ZoneOffset.UTC));
        assertThat(provider.extractPdf(new byte[]{1, 2, 3}).pages()).hasSize(1);
        server.verify();
    }
    @Test
    void unverifiedDocumentNeverLeavesServer() {
        var p = new KnowledgeOcrProperties("http", "https://ocr.safeai.test/api", "test-key",
                List.of("ocr.safeai.test"), 20, Duration.ofSeconds(1), Duration.ofSeconds(2),
                16L * 1024 * 1024, 25L * 1024 * 1024);
        var now = Instant.parse("2026-11-17T12:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var binding = KnowledgeProviderDataGuard.ocrBinding(p);
        var cases = Arrays.asList(null,
                new ProviderDataEvidence("DOC-TEST", now.minusSeconds(2), now, binding, true, true),
                new ProviderDataEvidence("DOC-TEST", now, now.plusSeconds(60), "0".repeat(64), true, true),
                new ProviderDataEvidence("DOC-TEST", now, now.plusSeconds(60), binding, false, true),
                new ProviderDataEvidence("DOC-TEST", now, now.plusSeconds(60), binding, true, false));
        for (var evidence : cases) {
            var builder = RestClient.builder().baseUrl(p.endpoint());
            var server = MockRestServiceServer.bindTo(builder).build();
            var provider = new HttpKnowledgeOcrProvider(p, builder.build(), evidence, clock);
            assertThatThrownBy(() -> provider.extractPdf(new byte[]{1, 2, 3}))
                    .isInstanceOf(KnowledgeIngestionException.class)
                    .extracting("code").isEqualTo("OCR_DATA_ASSURANCE_DENIED");
            server.verify();
        }
    }
}
