package ru.safeai.gateway.knowledge.service;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;

import java.util.Objects;

@Component
public class KnowledgeDocumentFileValidator {

    static final String PDF_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.PDF;
    static final String DOCX_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.DOCX;
    static final String XLSX_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.XLSX;
    static final String PPTX_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.PPTX;
    static final String HTML_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.HTML;
    static final String TEXT_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.TEXT;
    static final String MARKDOWN_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.MARKDOWN;
    static final String CSV_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.CSV;
    static final String JSON_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.JSON;
    static final String XML_MEDIA_TYPE =
            KnowledgeDocumentMediaTypes.XML;

    private final KnowledgeStorageProperties properties;
    private final KnowledgeDocumentMediaTypeDetector mediaTypeDetector;

    public KnowledgeDocumentFileValidator(
            KnowledgeStorageProperties properties
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
        this.mediaTypeDetector =
                new KnowledgeDocumentMediaTypeDetector();
    }

    public ValidatedUpload validate(
            MultipartFile file
    ) {
        KnowledgeUploadFileSupport.requireNonEmptyFile(file);

        long maxUploadBytes = properties.maxUploadBytes();
        KnowledgeUploadFileSupport.requireWithinUploadLimit(
                file.getSize(),
                maxUploadBytes
        );

        String originalFilename =
                KnowledgeUploadFileSupport.safeFilename(
                        file.getOriginalFilename()
                );
        String extension =
                KnowledgeUploadFileSupport.extension(
                        originalFilename
                );
        KnowledgeUploadFileSupport.requireSupportedExtension(
                extension
        );

        byte[] bytes = KnowledgeUploadFileSupport.readFileBytes(file);
        KnowledgeUploadFileSupport.requireWithinUploadLimit(
                bytes.length,
                maxUploadBytes
        );

        String mediaType = mediaTypeDetector.detect(
                bytes,
                extension
        );

        return new ValidatedUpload(
                bytes,
                originalFilename,
                mediaType,
                KnowledgeUploadFileSupport.sha256(bytes)
        );
    }

    public record ValidatedUpload(
            byte[] bytes,
            String originalFilename,
            String mediaType,
            String sha256
    ) {
        public ValidatedUpload {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public int sizeBytes() {
            return bytes.length;
        }
    }
}
