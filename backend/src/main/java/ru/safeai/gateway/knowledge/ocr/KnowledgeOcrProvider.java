package ru.safeai.gateway.knowledge.ocr;

public interface KnowledgeOcrProvider {

    boolean enabled();

    OcrDocument extractPdf(byte[] pdf);
}
