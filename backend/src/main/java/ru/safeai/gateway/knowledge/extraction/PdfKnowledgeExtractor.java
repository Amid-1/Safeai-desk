package ru.safeai.gateway.knowledge.extraction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.config.KnowledgeOcrProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;
import ru.safeai.gateway.knowledge.ocr.KnowledgeOcrProvider;
import ru.safeai.gateway.knowledge.ocr.OcrDocument;
import ru.safeai.gateway.knowledge.ocr.OcrPage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PdfKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION =
            "pdfbox-3.0.8-page-v2";

    private final KnowledgeIngestionProperties properties;
    private final KnowledgeOcrProperties ocrProperties;
    private final KnowledgeOcrProvider ocrProvider;

    @Autowired
    public PdfKnowledgeExtractor(
            KnowledgeIngestionProperties properties,
            KnowledgeOcrProperties ocrProperties,
            KnowledgeOcrProvider ocrProvider
    ) {
        this.properties = properties;
        this.ocrProperties = ocrProperties;
        this.ocrProvider = ocrProvider;
    }

    @Override
    public boolean supports(
            String mediaType
    ) {
        return "application/pdf".equalsIgnoreCase(
                mediaType
        );
    }

    @Override
    public ExtractedDocument extract(
            byte[] content
    ) {
        try (
                PDDocument document =
                        Loader.loadPDF(content)
        ) {
            if (document.isEncrypted()
                    && !document
                    .getCurrentAccessPermission()
                    .canExtractContent()) {
                throw new KnowledgeIngestionException(
                        "PDF_EXTRACTION_FORBIDDEN",
                        "PDF запрещает извлечение текста",
                        false
                );
            }

            int pageCount =
                    document.getNumberOfPages();

            PDFTextStripper stripper =
                    new PDFTextStripper();

            stripper.setSortByPosition(
                    true
            );

            List<ExtractedSection> sections =
                    new ArrayList<>();

            List<String> nativePages =
                    new ArrayList<>(
                            pageCount
                    );

            for (
                    int page = 1;
                    page <= pageCount;
                    page++
            ) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String text =
                        ExtractionTextSupport.normalize(
                                stripper.getText(
                                        document
                                )
                        );

                nativePages.add(text);
            }

            OcrDocument ocr =
                    requiresOcr(nativePages)
                            && ocrProvider.enabled()
                            ? ocrProvider.extractPdf(
                            content
                    )
                            : null;

            Map<Integer, String> ocrPages =
                    ocr == null
                            ? Map.of()
                            : validateAndMapOcrPages(
                            ocr,
                            pageCount
                    );

            int characterCount = 0;

            for (
                    int index = 0;
                    index < nativePages.size();
                    index++
            ) {
                String nativeText =
                        nativePages.get(index);

                String text =
                        nativeText.length()
                                >= ocrProperties
                                .minNativeCharsPerPage()
                                ? nativeText
                                : ocrPages.getOrDefault(
                                index + 1,
                                nativeText
                        );

                characterCount =
                        ExtractionTextSupport.addAndCheck(
                                characterCount,
                                text,
                                properties.maxExtractedChars()
                        );

                if (!text.isBlank()) {
                    sections.add(
                            new ExtractedSection(
                                    index + 1,
                                    null,
                                    text
                            )
                    );
                }
            }

            if (sections.isEmpty()) {
                throw new KnowledgeIngestionException(
                        "SCANNED_PDF_OCR_REQUIRED",
                        "PDF не содержит текст; настройте OCR provider",
                        false
                );
            }

            return new ExtractedDocument(
                    ocr == null
                            ? VERSION
                            : boundedVersion(
                            ocr.modelVersion()
                    ),
                    sections,
                    characterCount
            );
        } catch (IOException exception) {
            throw new KnowledgeIngestionException(
                    "INVALID_PDF",
                    "Не удалось безопасно извлечь текст из PDF",
                    false,
                    exception
            );
        }
    }

    private boolean requiresOcr(
            List<String> pages
    ) {
        return pages.stream()
                .anyMatch(
                        text -> text.length()
                                < ocrProperties
                                .minNativeCharsPerPage()
                );
    }

    private static Map<Integer, String> validateAndMapOcrPages(
            OcrDocument ocr,
            int pageCount
    ) {
        for (OcrPage page : ocr.pages()) {
            if (page.pageNumber() > pageCount) {
                throw new KnowledgeIngestionException(
                        "OCR_INVALID_RESPONSE",
                        "OCR provider вернул страницу за пределами PDF",
                        false
                );
            }
        }

        return ocr.pages()
                .stream()
                .collect(
                        Collectors.toUnmodifiableMap(
                                OcrPage::pageNumber,
                                page ->
                                        ExtractionTextSupport.normalize(
                                                page.text()
                                        )
                        )
                );
    }

    private static String boundedVersion(
            String ocrVersion
    ) {
        String value =
                VERSION
                        + "+"
                        + ocrVersion;

        return value.length() <= 128
                ? value
                : value.substring(
                        0,
                        128
                );
    }
}
