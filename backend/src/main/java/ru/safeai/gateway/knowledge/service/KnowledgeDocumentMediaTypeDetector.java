package ru.safeai.gateway.knowledge.service;

final class KnowledgeDocumentMediaTypeDetector {

    private final KnowledgeOoxmlDetector ooxmlDetector =
            new KnowledgeOoxmlDetector();

    String detect(
            byte[] bytes,
            String extension
    ) {
        String binaryMediaType = detectBinaryType(bytes);
        if (binaryMediaType != null) {
            if (!extensionMatches(extension, binaryMediaType)) {
                throw KnowledgeValidationErrors.extensionMismatch();
            }
            return binaryMediaType;
        }

        String text = KnowledgeStructuredTextValidator
                .decodeStrictUtf8(bytes);
        KnowledgeStructuredTextValidator.validateTextControls(text);

        return switch (extension) {
            case "txt" -> {
                if (KnowledgeStructuredTextValidator.looksLikeHtml(text)) {
                    throw KnowledgeValidationErrors.extensionMismatch();
                }
                yield KnowledgeDocumentMediaTypes.TEXT;
            }
            case "html", "htm" -> {
                if (!KnowledgeStructuredTextValidator.looksLikeHtml(text)) {
                    throw KnowledgeValidationErrors.extensionMismatch();
                }
                yield KnowledgeDocumentMediaTypes.HTML;
            }
            case "md" -> KnowledgeDocumentMediaTypes.MARKDOWN;
            case "csv" -> KnowledgeDocumentMediaTypes.CSV;
            case "json" -> {
                KnowledgeStructuredTextValidator.validateJson(text);
                yield KnowledgeDocumentMediaTypes.JSON;
            }
            case "xml" -> {
                KnowledgeStructuredTextValidator.validateXml(text);
                yield KnowledgeDocumentMediaTypes.XML;
            }
            case "pdf", "docx", "xlsx", "pptx" ->
                    throw KnowledgeValidationErrors.extensionMismatch();
            default -> throw KnowledgeValidationErrors.unsupportedType();
        };
    }

    private String detectBinaryType(
            byte[] bytes
    ) {
        if (isPdf(bytes)) {
            return KnowledgeDocumentMediaTypes.PDF;
        }
        return ooxmlDetector.detect(bytes);
    }

    private static boolean isPdf(
            byte[] bytes
    ) {
        if (bytes.length < 10
                || bytes[0] != '%'
                || bytes[1] != 'P'
                || bytes[2] != 'D'
                || bytes[3] != 'F'
                || bytes[4] != '-') {
            return false;
        }

        byte[] marker = new byte[]{'%', '%', 'E', 'O', 'F'};
        int start = Math.max(0, bytes.length - 4 * 1024);

        for (int index = bytes.length - marker.length;
             index >= start;
             index--) {
            if (matchesAt(bytes, marker, index)) {
                return true;
            }
        }

        return false;
    }

    private static boolean matchesAt(
            byte[] source,
            byte[] marker,
            int start
    ) {
        for (int index = 0; index < marker.length; index++) {
            if (source[start + index] != marker[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean extensionMatches(
            String extension,
            String mediaType
    ) {
        return switch (mediaType) {
            case KnowledgeDocumentMediaTypes.PDF ->
                    "pdf".equals(extension);
            case KnowledgeDocumentMediaTypes.DOCX ->
                    "docx".equals(extension);
            case KnowledgeDocumentMediaTypes.XLSX ->
                    "xlsx".equals(extension);
            case KnowledgeDocumentMediaTypes.PPTX ->
                    "pptx".equals(extension);
            default -> false;
        };
    }
}
