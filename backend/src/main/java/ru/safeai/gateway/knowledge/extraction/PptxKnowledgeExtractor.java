package ru.safeai.gateway.knowledge.extraction;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class PptxKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".presentationml.presentation";
    private static final String VERSION = "poi-5.5.1-pptx-slide-v1";

    private final KnowledgeIngestionProperties properties;

    public PptxKnowledgeExtractor(KnowledgeIngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return MEDIA_TYPE.equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        OoxmlPackageSupport.validate(
                content,
                "ppt/presentation.xml",
                properties.maxDocxUncompressedBytes(),
                "PPTX"
        );

        try (XMLSlideShow presentation = new XMLSlideShow(
                new ByteArrayInputStream(content)
        )) {
            List<ExtractedSection> sections = new ArrayList<>();
            int characterCount = 0;
            List<XSLFSlide> slides = presentation.getSlides();
            for (int index = 0; index < slides.size(); index++) {
                XSLFSlide slide = slides.get(index);
                String text = extractShapes(slide.getShapes());
                if (text.isBlank()) {
                    continue;
                }
                characterCount = ExtractionTextSupport.addAndCheck(
                        characterCount,
                        text,
                        properties.maxExtractedChars()
                );
                sections.add(new ExtractedSection(
                        index + 1,
                        heading(slide, index + 1),
                        text
                ));
            }
            return new ExtractedDocument(VERSION, sections, characterCount);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof KnowledgeIngestionException ingestion) {
                throw ingestion;
            }
            throw new KnowledgeIngestionException(
                    "INVALID_PPTX",
                    "Не удалось безопасно извлечь текст из PPTX",
                    false,
                    exception
            );
        }
    }

    private String extractShapes(List<XSLFShape> shapes) {
        StringBuilder output = new StringBuilder();
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTable table) {
                appendTable(output, table);
            } else if (shape instanceof XSLFTextShape textShape) {
                appendText(output, textShape.getText());
            } else if (shape instanceof XSLFGroupShape group) {
                appendText(output, extractShapes(group.getShapes()));
            }
            if (output.length() > properties.maxExtractedChars()) {
                throw ExtractionTextSupport.tooLarge();
            }
        }
        return ExtractionTextSupport.normalize(output.toString());
    }

    private static void appendTable(StringBuilder output, XSLFTable table) {
        for (XSLFTableRow row : table) {
            StringBuilder renderedRow = new StringBuilder();
            for (XSLFTableCell cell : row) {
                String text = ExtractionTextSupport.normalize(cell.getText())
                        .replace('\n', ' ');
                if (text.isBlank()) {
                    continue;
                }
                if (!renderedRow.isEmpty()) {
                    renderedRow.append(" | ");
                }
                renderedRow.append(text);
            }
            appendText(output, renderedRow.toString());
        }
    }

    private static void appendText(StringBuilder output, String value) {
        String normalized = ExtractionTextSupport.normalize(value);
        if (normalized.isBlank()) {
            return;
        }
        if (!output.isEmpty()) {
            output.append('\n');
        }
        output.append(normalized);
    }

    private static String heading(XSLFSlide slide, int slideNumber) {
        String title = slide.getTitle();
        return title == null || title.isBlank()
                ? "Слайд " + slideNumber
                : title.strip();
    }
}
