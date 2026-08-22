package ru.safeai.gateway.knowledge.ocr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "safeai.knowledge.ocr.provider",
        havingValue = "disabled",
        matchIfMissing = true
)
public class DisabledKnowledgeOcrProvider implements KnowledgeOcrProvider {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public OcrDocument extractPdf(byte[] pdf) {
        throw new IllegalStateException("OCR provider is disabled");
    }
}
