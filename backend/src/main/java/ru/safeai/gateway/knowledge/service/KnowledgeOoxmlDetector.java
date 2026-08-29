package ru.safeai.gateway.knowledge.service;

import ru.safeai.gateway.common.exception.BadRequestException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class KnowledgeOoxmlDetector {

    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final int MAX_CONTENT_TYPES_BYTES = 256 * 1024;

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

        boolean wordDocumentFound = false;
        boolean workbookFound = false;
        boolean presentationFound = false;
        byte[] contentTypesBytes = null;

        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(bytes)
        )) {
            ZipEntry entry;
            int entries = 0;

            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    return null;
                }

                String name = entry.getName();
                if (isUnsafeZipEntryName(name)) {
                    return null;
                }

                switch (name) {
                    case "word/document.xml" -> {
                        if (wordDocumentFound) {
                            return null;
                        }
                        wordDocumentFound = true;
                    }
                    case "xl/workbook.xml" -> {
                        if (workbookFound) {
                            return null;
                        }
                        workbookFound = true;
                    }
                    case "ppt/presentation.xml" -> {
                        if (presentationFound) {
                            return null;
                        }
                        presentationFound = true;
                    }
                    case "[Content_Types].xml" -> {
                        if (contentTypesBytes != null) {
                            return null;
                        }
                        contentTypesBytes = readContentTypesEntry(zip);
                    }
                    default -> {
                        // Structural upload validation only.
                    }
                }
            }
        } catch (IOException exception) {
            return null;
        }

        if (contentTypesBytes == null) {
            return null;
        }

        OoxmlMainTypes mainTypes =
                parseOoxmlMainTypes(contentTypesBytes);
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

    private static boolean isUnsafeZipEntryName(
            String name
    ) {
        return name.isBlank()
                || name.startsWith("/")
                || name.contains("\\")
                || name.equals("..")
                || name.startsWith("../")
                || name.contains("/../");
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

    private static byte[] readContentTypesEntry(
            ZipInputStream zip
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;

        while ((read = zip.read(buffer)) != -1) {
            total = Math.addExact(total, read);
            if (total > MAX_CONTENT_TYPES_BYTES) {
                throw new IOException(
                        "OOXML [Content_Types].xml exceeds validation limit"
                );
            }
            output.write(buffer, 0, read);
        }

        return output.toByteArray();
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
