package ru.safeai.gateway.knowledge.service;

final class KnowledgeDocumentMediaTypes {

    static final String PDF = "application/pdf";
    static final String DOCX =
            "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document";
    static final String XLSX =
            "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet";
    static final String PPTX =
            "application/vnd.openxmlformats-officedocument"
                    + ".presentationml.presentation";
    static final String HTML = "text/html";
    static final String TEXT = "text/plain";
    static final String MARKDOWN = "text/markdown";
    static final String CSV = "text/csv";
    static final String JSON = "application/json";
    static final String XML = "application/xml";

    private KnowledgeDocumentMediaTypes() {
    }
}
