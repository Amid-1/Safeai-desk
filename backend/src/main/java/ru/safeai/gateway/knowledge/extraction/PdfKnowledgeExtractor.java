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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class PdfKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION =
            "pdfbox-3.0.8-page-v3";

    private final KnowledgeIngestionProperties properties;
    private final KnowledgeOcrProperties ocrProperties;
    private final KnowledgeOcrProvider ocrProvider;

    @Autowired
    public PdfKnowledgeExtractor(
            KnowledgeIngestionProperties properties,
            KnowledgeOcrProperties ocrProperties,
            KnowledgeOcrProvider ocrProvider
    ) {
        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties не должен быть null"
                );

        this.ocrProperties =
                Objects.requireNonNull(
                        ocrProperties,
                        "ocrProperties не должен быть null"
                );

        this.ocrProvider =
                Objects.requireNonNull(
                        ocrProvider,
                        "ocrProvider не должен быть null"
                );
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
        Objects.requireNonNull(
                content,
                "content не должен быть null"
        );

        try (
                PDDocument document =
                        Loader.loadPDF(
                                content
                        )
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

            List<String> nativePages =
                    new ArrayList<>(
                            pageCount
                    );

            /*
             * Извлекаем native text отдельно для каждой страницы.
             *
             * Это необходимо не только для сохранения page provenance,
             * но и для точного определения страниц, которым действительно
             * нужен OCR.
             */
            for (
                    int page = 1;
                    page <= pageCount;
                    page++
            ) {
                stripper.setStartPage(
                        page
                );

                stripper.setEndPage(
                        page
                );

                String text =
                        ExtractionTextSupport.normalize(
                                stripper.getText(
                                        document
                                )
                        );

                nativePages.add(
                        text
                );
            }

            /*
             * Важный invariant:
             *
             * OCR completeness проверяется относительно конкретных страниц,
             * для которых native text ниже установленного threshold.
             */
            Set<Integer> requiredOcrPages =
                    requiredOcrPages(
                            nativePages
                    );

            OcrDocument ocr =
                    !requiredOcrPages.isEmpty()
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
                                    pageCount,
                                    requiredOcrPages
                            );

            List<ExtractedSection> sections =
                    new ArrayList<>();

            int characterCount =
                    0;

            for (
                    int index = 0;
                    index < nativePages.size();
                    index++
            ) {
                int pageNumber =
                        index + 1;

                String nativeText =
                        nativePages.get(
                                index
                        );

                /*
                 * Если OCR действительно выполнялся для required page,
                 * отсутствие страницы здесь уже невозможно:
                 * validateAndMapOcrPages() fail-closed проверил containsAll().
                 *
                 * Поэтому get(), а не getOrDefault(), является намеренным.
                 */
                String text =
                        requiredOcrPages.contains(
                                pageNumber
                        )
                                && ocr != null
                                ? ocrPages.get(
                                        pageNumber
                                )
                                : nativeText;

                characterCount =
                        ExtractionTextSupport.addAndCheck(
                                characterCount,
                                text,
                                properties.maxExtractedChars()
                        );

                if (!text.isBlank()) {
                    sections.add(
                            new ExtractedSection(
                                    pageNumber,
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

    /**
     * Returns exact 1-based PDF page numbers that require OCR.
     */
    private Set<Integer> requiredOcrPages(
            List<String> pages
    ) {
        Set<Integer> required =
                new LinkedHashSet<>();

        for (
                int index = 0;
                index < pages.size();
                index++
        ) {
            String text =
                    pages.get(
                            index
                    );

            if (text.length()
                    < ocrProperties
                    .minNativeCharsPerPage()) {

                required.add(
                        index + 1
                );
            }
        }

        return Set.copyOf(
                required
        );
    }

    /**
     * Validates OCR response against the actual PDF and against the set of
     * pages for which OCR was required.
     *
     * <p>A page being present with blank OCR text is intentionally different
     * from that page being absent from the response. Presence satisfies the
     * protocol completeness invariant; downstream extraction may still decide
     * whether any usable text exists.</p>
     */
    private static Map<Integer, String> validateAndMapOcrPages(
            OcrDocument ocr,
            int pageCount,
            Set<Integer> requiredOcrPages
    ) {
        if (ocr == null
                || ocr.pages() == null) {
            throw invalidOcrResponse(
                    "OCR provider вернул пустой response"
            );
        }

        Map<Integer, String> pages =
                new LinkedHashMap<>();

        for (OcrPage page : ocr.pages()) {
            if (page == null) {
                throw invalidOcrResponse(
                        "OCR provider вернул null page"
                );
            }

            int pageNumber =
                    page.pageNumber();

            if (pageNumber < 1
                    || pageNumber > pageCount) {

                throw invalidOcrResponse(
                        "OCR provider вернул страницу "
                                + "за пределами PDF"
                );
            }

            /*
             * Проверяем duplicate до put().
             *
             * Это надёжнее проверки previous != null и не зависит от того,
             * может ли mapped OCR text когда-либо быть null.
             */
            if (pages.containsKey(
                    pageNumber
            )) {
                throw invalidOcrResponse(
                        "OCR provider продублировал страницу"
                );
            }

            String normalizedText =
                    ExtractionTextSupport.normalize(
                            page.text()
                    );

            pages.put(
                    pageNumber,
                    normalizedText
            );
        }

        /*
         * Критический completeness invariant.
         *
         * Если OCR был вызван из-за страниц 2, 7 и 9, provider обязан явно
         * вернуть 2, 7 и 9.
         *
         * Нельзя молча fallback'иться на слабый native text.
         */
        if (!pages.keySet()
                .containsAll(
                        requiredOcrPages
                )) {

            throw invalidOcrResponse(
                    "OCR provider пропустил обязательные страницы"
            );
        }

        return Map.copyOf(
                pages
        );
    }

    private static String boundedVersion(
            String ocrVersion
    ) {
        String normalizedOcrVersion =
                ocrVersion == null
                        || ocrVersion.isBlank()
                        ? "unknown"
                        : ocrVersion.strip();

        String value =
                VERSION
                        + "+"
                        + normalizedOcrVersion;

        return value.length() <= 128
                ? value
                : value.substring(
                        0,
                        128
                );
    }

    private static KnowledgeIngestionException invalidOcrResponse(
            String message
    ) {
        return new KnowledgeIngestionException(
                "OCR_INVALID_RESPONSE",
                message,
                false
        );
    }
}