package ru.safeai.gateway.knowledge.service;

import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.knowledge.extraction.OoxmlPackageSupport;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;

final class KnowledgeOoxmlDetector {

    private static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 100L * 1024L * 1024L;

    private final long maximumUncompressedBytes;

    KnowledgeOoxmlDetector() {
        this(DEFAULT_MAX_UNCOMPRESSED_BYTES);
    }

    KnowledgeOoxmlDetector(long maximumUncompressedBytes) {
        if (maximumUncompressedBytes <= 0) {
            throw new IllegalArgumentException("maximumUncompressedBytes must be positive");
        }
        this.maximumUncompressedBytes = maximumUncompressedBytes;
    }

    private static final String DOCX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document.main+xml";
    private static final String XLSX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet.main+xml";
    private static final String PPTX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".presentationml.presentation.main+xml";

    String detect(
            byte[] bytes
    ) {
        if (!hasZipLocalHeader(bytes)) {
            return null;
        }

        final OoxmlPackageSupport.ArchiveIndex archive;
        try {
            archive = OoxmlPackageSupport.inspect(
                    bytes, maximumUncompressedBytes, "OOXML");
        } catch (KnowledgeIngestionException exception) {
            // Upload validation preserves the established BadRequest/extension
            // mismatch contract instead of leaking an ingestion error.
            return null;
        }

        if (archive.count("[Content_Types].xml") != 1
                || archive.contentTypes() == null) {
            return null;
        }
        boolean wordDocumentFound = archive.count("word/document.xml") == 1;
        boolean workbookFound = archive.count("xl/workbook.xml") == 1;
        boolean presentationFound = archive.count("ppt/presentation.xml") == 1;
        if (archive.count("word/document.xml") > 1
                || archive.count("xl/workbook.xml") > 1
                || archive.count("ppt/presentation.xml") > 1) {
            return null;
        }

        OoxmlMainTypes mainTypes =
                parseOoxmlMainTypes(archive.contentTypes());
        if (mainTypes == null) {
            return null;
        }

        boolean docx = wordDocumentFound && mainTypes.docx();
        boolean xlsx = workbookFound && mainTypes.xlsx();
        boolean pptx = presentationFound && mainTypes.pptx();

        int matches = (docx ? 1 : 0)
                + (xlsx ? 1 : 0)
                + (pptx ? 1 : 0);
        if (matches != 1) {
            return null;
        }

        if (docx) {
            return KnowledgeDocumentMediaTypes.DOCX;
        }
        if (xlsx) {
            return KnowledgeDocumentMediaTypes.XLSX;
        }
        return KnowledgeDocumentMediaTypes.PPTX;
    }

    private static boolean hasZipLocalHeader(
            byte[] bytes
    ) {
        return bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && bytes[2] == 3
                && bytes[3] == 4;
    }

    private static OoxmlMainTypes parseOoxmlMainTypes(
            byte[] contentTypesBytes
    ) {
        final String contentTypesText;
        try {
            contentTypesText =
                    KnowledgeStructuredTextValidator
                            .decodeStrictUtf8(contentTypesBytes);
        } catch (BadRequestException exception) {
            return null;
        }

        if (KnowledgeStructuredTextValidator
                .containsForbiddenXmlDeclaration(contentTypesText)) {
            return null;
        }

        XMLInputFactory factory =
                KnowledgeStructuredTextValidator.secureXmlInputFactory();
        boolean docx = false;
        boolean xlsx = false;
        boolean pptx = false;
        XMLStreamReader reader = null;

        try {
            reader = factory.createXMLStreamReader(
                    new StringReader(
                            KnowledgeStructuredTextValidator
                                    .stripUtf8Bom(contentTypesText)
                    )
            );

            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT
                        || !"Override".equals(reader.getLocalName())) {
                    continue;
                }

                String partName = reader.getAttributeValue(
                        null,
                        "PartName"
                );
                String contentType = reader.getAttributeValue(
                        null,
                        "ContentType"
                );

                if ("/word/document.xml".equals(partName)
                        && DOCX_MAIN_CONTENT_TYPE.equals(contentType)) {
                    docx = true;
                }
                if ("/xl/workbook.xml".equals(partName)
                        && XLSX_MAIN_CONTENT_TYPE.equals(contentType)) {
                    xlsx = true;
                }
                if ("/ppt/presentation.xml".equals(partName)
                        && PPTX_MAIN_CONTENT_TYPE.equals(contentType)) {
                    pptx = true;
                }
            }

            return new OoxmlMainTypes(docx, xlsx, pptx);
        } catch (XMLStreamException exception) {
            return null;
        } finally {
            KnowledgeStructuredTextValidator.closeQuietly(reader);
        }
    }

    private record OoxmlMainTypes(
            boolean docx,
            boolean xlsx,
            boolean pptx
    ) {
    }
}
