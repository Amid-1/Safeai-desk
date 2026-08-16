package ru.safeai.gateway.knowledge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@RequiredArgsConstructor
public class KnowledgeDocumentFileValidator {

    static final String PDF_MEDIA_TYPE =
            "application/pdf";

    static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    static final String PPTX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    static final String HTML_MEDIA_TYPE =
            "text/html";

    static final String TEXT_MEDIA_TYPE =
            "text/plain";

    static final String MARKDOWN_MEDIA_TYPE =
            "text/markdown";

    static final String CSV_MEDIA_TYPE =
            "text/csv";

    static final String JSON_MEDIA_TYPE =
            "application/json";

    static final String XML_MEDIA_TYPE =
            "application/xml";

    private static final String SUPPORTED_FORMATS =
            "PDF, DOCX, TXT, HTML, MD, CSV, XLSX, PPTX, JSON и XML";

    private static final int MAX_FILENAME_CODE_POINTS =
            255;

    private static final int MAX_ZIP_ENTRIES =
            10_000;

    private static final int MAX_CONTENT_TYPES_BYTES =
            256 * 1024;

    private static final int PDF_EOF_SCAN_BYTES =
            4 * 1024;

    private static final int MAX_JSON_DEPTH =
            128;

    private static final String DOCX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    private static final String XLSX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";

    private static final String PPTX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(
                    "pdf",
                    "docx",
                    "txt",
                    "html",
                    "htm",
                    "md",
                    "csv",
                    "xlsx",
                    "pptx",
                    "json",
                    "xml"
            );

    private final KnowledgeStorageProperties properties;

    public ValidatedUpload validate(
            MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(
                    "Выберите непустой файл."
            );
        }

        long maxUploadBytes =
                properties.maxUploadBytes();

        if (file.getSize() > maxUploadBytes) {
            throw fileTooLarge(
                    maxUploadBytes
            );
        }

        String originalFilename =
                safeFilename(
                        file.getOriginalFilename()
                );

        String extension =
                extension(
                        originalFilename
                );

        requireSupportedExtension(
                extension
        );

        final byte[] bytes;

        try {
            bytes =
                    file.getBytes();
        } catch (IOException exception) {
            throw new BadRequestException(
                    "Не удалось прочитать загружаемый файл.",
                    exception
            );
        }

        if (bytes.length == 0) {
            throw new BadRequestException(
                    "Выберите непустой файл."
            );
        }

        if (bytes.length > maxUploadBytes) {
            throw fileTooLarge(
                    maxUploadBytes
            );
        }

        String mediaType =
                detectTypeForExtension(
                        bytes,
                        extension
                );

        return new ValidatedUpload(
                bytes,
                originalFilename,
                mediaType,
                sha256(bytes)
        );
    }

        private static String detectTypeForExtension(
            byte[] bytes,
            String extension
    ) {
        String binaryMediaType =
                detectBinaryType(
                        bytes
                );

        if (binaryMediaType != null) {
            if (
                    !extensionMatches(
                            extension,
                            binaryMediaType
                    )
            ) {
                throw extensionMismatch();
            }

            return binaryMediaType;
        }

        String text =
                decodeStrictUtf8(
                        bytes
                );

        validateTextControls(
                text
        );

        return switch (extension) {
            case "txt" -> {
                if (looksLikeHtml(text)) {
                    throw extensionMismatch();
                }

                yield TEXT_MEDIA_TYPE;
            }

            case "html", "htm" -> {
                if (!looksLikeHtml(text)) {
                    throw extensionMismatch();
                }

                yield HTML_MEDIA_TYPE;
            }

            case "md" ->
                    MARKDOWN_MEDIA_TYPE;

            case "csv" ->
                    CSV_MEDIA_TYPE;

            case "json" -> {
                validateJson(
                        text
                );

                yield JSON_MEDIA_TYPE;
            }

            case "xml" -> {
                validateXml(
                        text
                );

                yield XML_MEDIA_TYPE;
            }

            /*
             * Если PDF/OOXML не был распознан по фактическим байтам,
             * текст под бинарным расширением не принимаем.
             */
            case "pdf",
                 "docx",
                 "xlsx",
                 "pptx" ->
                    throw extensionMismatch();

            default ->
                    throw unsupportedType();
        };
    }

    private static String detectBinaryType(
            byte[] bytes
    ) {
        if (isPdf(bytes)) {
            return PDF_MEDIA_TYPE;
        }

        return detectOoxmlType(
                bytes
        );
    }

    private static boolean isPdf(
            byte[] bytes
    ) {
        if (
                bytes.length < 10
                || bytes[0] != '%'
                || bytes[1] != 'P'
                || bytes[2] != 'D'
                || bytes[3] != 'F'
                || bytes[4] != '-'
        ) {
            return false;
        }

        byte[] marker =
                "%%EOF".getBytes(
                        StandardCharsets.US_ASCII
                );

        int start =
                Math.max(
                        0,
                        bytes.length
                                - PDF_EOF_SCAN_BYTES
                );

        for (
                int i =
                        bytes.length
                                - marker.length;
                i >= start;
                i--
        ) {
            boolean matches =
                    true;

            for (
                    int j = 0;
                    j < marker.length;
                    j++
            ) {
                if (
                        bytes[i + j]
                        != marker[j]
                ) {
                    matches =
                            false;
                    break;
                }
            }

            if (matches) {
                return true;
            }
        }

        return false;
    }

    private static String detectOoxmlType(
            byte[] bytes
    ) {
        if (!hasZipLocalHeader(bytes)) {
            return null;
        }

        boolean wordDocumentFound =
                false;

        boolean workbookFound =
                false;

        boolean presentationFound =
                false;

        byte[] contentTypesBytes =
                null;

        try (
                ZipInputStream zip =
                        new ZipInputStream(
                                new ByteArrayInputStream(
                                        bytes
                                )
                        )
        ) {
            ZipEntry entry;
            int entries = 0;

            while (
                    (entry = zip.getNextEntry())
                            != null
            ) {
                entries++;

                if (
                        entries
                        > MAX_ZIP_ENTRIES
                ) {
                    return null;
                }

                String name =
                        entry.getName();

                switch (name) {
                    case "word/document.xml" ->
                            wordDocumentFound = true;

                    case "xl/workbook.xml" ->
                            workbookFound = true;

                    case "ppt/presentation.xml" ->
                            presentationFound = true;

                    case "[Content_Types].xml" -> {
                        if (
                                contentTypesBytes
                                != null
                        ) {
                            /*
                             * Дубликат центрального OOXML descriptor
                             * считаем подозрительным/неоднозначным.
                             */
                            return null;
                        }

                        contentTypesBytes =
                                readContentTypesEntry(
                                        zip
                                );
                    }

                    default -> {
                        // Остальные ZIP entries для upload-level
                        // structural validation читать не требуется.
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
                parseOoxmlMainTypes(
                        contentTypesBytes
                );

        if (mainTypes == null) {
            return null;
        }

        boolean docx =
                wordDocumentFound
                && mainTypes.docx();

        boolean xlsx =
                workbookFound
                && mainTypes.xlsx();

        boolean pptx =
                presentationFound
                && mainTypes.pptx();

        int matches =
                (docx ? 1 : 0)
                + (xlsx ? 1 : 0)
                + (pptx ? 1 : 0);

        if (matches != 1) {
            return null;
        }

        if (docx) {
            return DOCX_MEDIA_TYPE;
        }

        if (xlsx) {
            return XLSX_MEDIA_TYPE;
        }

        return PPTX_MEDIA_TYPE;
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
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        byte[] buffer =
                new byte[4096];

        int total =
                0;

        int read;

        while (
                (read = zip.read(buffer))
                        != -1
        ) {
            total += read;

            if (
                    total
                    > MAX_CONTENT_TYPES_BYTES
            ) {
                throw new IOException(
                        "OOXML [Content_Types].xml exceeds validation limit"
                );
            }

            output.write(
                    buffer,
                    0,
                    read
            );
        }

        return output.toByteArray();
    }

    private static OoxmlMainTypes parseOoxmlMainTypes(
            byte[] contentTypesBytes
    ) {
        String contentTypesText;

        try {
            contentTypesText =
                    decodeStrictUtf8(
                            contentTypesBytes
                    );
        } catch (BadRequestException exception) {
            return null;
        }

        if (
                containsForbiddenXmlDeclaration(
                        contentTypesText
                )
        ) {
            return null;
        }

        XMLInputFactory factory =
                secureXmlInputFactory();

        boolean docx =
                false;

        boolean xlsx =
                false;

        boolean pptx =
                false;

        XMLStreamReader reader =
                null;

        try {
            reader =
                    factory.createXMLStreamReader(
                            new StringReader(
                                    stripUtf8Bom(
                                            contentTypesText
                                    )
                            )
                    );

            while (reader.hasNext()) {
                int event =
                        reader.next();

                if (
                        event
                        != XMLStreamConstants.START_ELEMENT
                        || !"Override".equals(
                                reader.getLocalName()
                        )
                ) {
                    continue;
                }

                String partName =
                        reader.getAttributeValue(
                                null,
                                "PartName"
                        );

                String contentType =
                        reader.getAttributeValue(
                                null,
                                "ContentType"
                        );

                if (
                        "/word/document.xml"
                                .equals(partName)
                        && DOCX_MAIN_CONTENT_TYPE
                                .equals(contentType)
                ) {
                    docx =
                            true;
                }

                if (
                        "/xl/workbook.xml"
                                .equals(partName)
                        && XLSX_MAIN_CONTENT_TYPE
                                .equals(contentType)
                ) {
                    xlsx =
                            true;
                }

                if (
                        "/ppt/presentation.xml"
                                .equals(partName)
                        && PPTX_MAIN_CONTENT_TYPE
                                .equals(contentType)
                ) {
                    pptx =
                            true;
                }
            }

            return new OoxmlMainTypes(
                    docx,
                    xlsx,
                    pptx
            );
        } catch (XMLStreamException exception) {
            return null;
        } finally {
            closeQuietly(
                    reader
            );
        }
    }

    private static String decodeStrictUtf8(
            byte[] bytes
    ) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT
                    )
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT
                    )
                    .decode(
                            ByteBuffer.wrap(
                                    bytes
                            )
                    )
                    .toString();
        } catch (CharacterCodingException exception) {
            throw unsupportedType();
        }
    }

    private static void validateTextControls(
            String value
    ) {
        value.codePoints()
                .forEach(
                        codePoint -> {
                            if (
                                    Character.isISOControl(
                                            codePoint
                                    )
                                    && codePoint
                                    != '\n'
                                    && codePoint
                                    != '\r'
                                    && codePoint
                                    != '\t'
                            ) {
                                throw unsupportedType();
                            }
                        }
                );
    }

    private static boolean looksLikeHtml(
            String value
    ) {
        String normalizedHead =
                stripUtf8Bom(
                        value
                )
                        .stripLeading()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return normalizedHead
                .startsWith(
                        "<!doctype html"
                )
                || normalizedHead
                .startsWith(
                        "<html"
                );
    }

    private static void validateJson(
            String value
    ) {
        new StrictJsonParser(
                stripUtf8Bom(
                        value
                )
        ).parseDocument();
    }

    private static void validateXml(
            String value
    ) {
        String xml =
                stripUtf8Bom(
                        value
                );

        if (
                containsForbiddenXmlDeclaration(
                        xml
                )
        ) {
            throw invalidStructuredFormat(
                    "XML"
            );
        }

        XMLInputFactory factory =
                secureXmlInputFactory();

        XMLStreamReader reader =
                null;

        boolean rootElementFound =
                false;

        try {
            reader =
                    factory.createXMLStreamReader(
                            new StringReader(
                                    xml
                            )
                    );

            while (reader.hasNext()) {
                int event =
                        reader.next();

                if (
                        event
                        == XMLStreamConstants.START_ELEMENT
                ) {
                    rootElementFound =
                            true;
                }
            }
        } catch (XMLStreamException exception) {
            throw invalidStructuredFormat(
                    "XML"
            );
        } finally {
            closeQuietly(
                    reader
            );
        }

        if (!rootElementFound) {
            throw invalidStructuredFormat(
                    "XML"
            );
        }
    }

    private static boolean containsForbiddenXmlDeclaration(
            String value
    ) {
        String normalized =
                value.toLowerCase(
                        Locale.ROOT
                );

        return normalized.contains(
                "<!doctype"
        )
                || normalized.contains(
                "<!entity"
        );
    }

    private static XMLInputFactory secureXmlInputFactory() {
        XMLInputFactory factory =
                XMLInputFactory.newFactory();

        factory.setProperty(
                XMLInputFactory.SUPPORT_DTD,
                false
        );

        factory.setProperty(
                XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,
                false
        );

        factory.setProperty(
                XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES,
                false
        );

        return factory;
    }

    private static void closeQuietly(
            XMLStreamReader reader
    ) {
        if (reader == null) {
            return;
        }

        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // Validation уже завершена; close не должен менять результат.
        }
    }

    private static String stripUtf8Bom(
            String value
    ) {
        return !value.isEmpty()
                && value.charAt(0)
                == '\uFEFF'
                ? value.substring(1)
                : value;
    }

    private static String safeFilename(
            String value
    ) {
        requireOriginalFilename(
                value
        );

        String filename =
                filenameBasename(
                        value
                );

        validateFilename(
                filename
        );

        return filename;
    }

    private static void requireOriginalFilename(
            String value
    ) {
        if (
                value == null
                || value.isBlank()
        ) {
            throw new BadRequestException(
                    "Исходное имя файла отсутствует."
            );
        }
    }

    private static String filenameBasename(
            String value
    ) {
        String normalizedPath =
                value.replace(
                        '\\',
                        '/'
                );

        return normalizedPath
                .substring(
                        normalizedPath
                                .lastIndexOf('/')
                                + 1
                )
                .strip();
    }

    private static void validateFilename(
            String filename
    ) {
        if (filename.isEmpty()) {
            throw new BadRequestException(
                    "Исходное имя файла отсутствует."
            );
        }

        if (
                filename.codePointCount(
                        0,
                        filename.length()
                )
                > MAX_FILENAME_CODE_POINTS
        ) {
            throw new BadRequestException(
                    "Имя файла не должно превышать "
                            + MAX_FILENAME_CODE_POINTS
                            + " символов."
            );
        }

        boolean hasControl =
                filename.codePoints()
                        .anyMatch(
                                Character::isISOControl
                        );

        if (hasControl) {
            throw new BadRequestException(
                    "Имя файла содержит недопустимые управляющие символы."
            );
        }
    }

    private static void requireSupportedExtension(
            String extension
    ) {
        if (
                !SUPPORTED_EXTENSIONS.contains(
                        extension
                )
        ) {
            throw unsupportedType();
        }
    }

    private static boolean extensionMatches(
            String extension,
            String mediaType
    ) {
        return switch (mediaType) {
            case PDF_MEDIA_TYPE ->
                    "pdf".equals(
                            extension
                    );

            case DOCX_MEDIA_TYPE ->
                    "docx".equals(
                            extension
                    );

            case XLSX_MEDIA_TYPE ->
                    "xlsx".equals(
                            extension
                    );

            case PPTX_MEDIA_TYPE ->
                    "pptx".equals(
                            extension
                    );

            default ->
                    false;
        };
    }

    private static String extension(
            String filename
    ) {
        int dot =
                filename.lastIndexOf(
                        '.'
                );

        if (
                dot <= 0
                || dot
                == filename.length() - 1
        ) {
            return "";
        }

        return filename
                .substring(
                        dot + 1
                )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private static String sha256(
            byte[] bytes
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    bytes
                            )
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 недоступен в текущем Java runtime",
                    exception
            );
        }
    }

    private static BadRequestException unsupportedType() {
        return new BadRequestException(
                "Поддерживаются только "
                        + SUPPORTED_FORMATS
                        + "."
        );
    }

    private static BadRequestException extensionMismatch() {
        return new BadRequestException(
                "Расширение файла не соответствует его содержимому. "
                        + "Поддерживаются "
                        + SUPPORTED_FORMATS
                        + "."
        );
    }

    private static BadRequestException invalidStructuredFormat(
            String format
    ) {
        return new BadRequestException(
                "Файл "
                        + format
                        + " повреждён или имеет некорректную структуру."
        );
    }

    private static BadRequestException fileTooLarge(
            long maxUploadBytes
    ) {
        return new BadRequestException(
                "Размер файла превышает допустимый лимит: "
                        + maxUploadBytes
                        + " байт."
        );
    }

    public record ValidatedUpload(
            byte[] bytes,
            String originalFilename,
            String mediaType,
            String sha256
    ) {
    }

    private record OoxmlMainTypes(
            boolean docx,
            boolean xlsx,
            boolean pptx
    ) {
    }

    /**
     * Небольшой strict JSON syntax validator без привязки к Jackson 2/3.
     *
     * <p>Это намеренно upload-level validation: JSON не материализуется
     * в object tree, поэтому большой документ не создаёт дополнительную
     * память пропорционально количеству JSON nodes.</p>
     */
    private static final class StrictJsonParser {

        private final String input;

        private int index;

        private StrictJsonParser(
                String input
        ) {
            this.input =
                    input;
        }

        private void parseDocument() {
            skipWhitespace();

            parseValue(
                    0
            );

            skipWhitespace();

            if (
                    index
                    != input.length()
            ) {
                fail();
            }
        }

        private void parseValue(
                int depth
        ) {
            if (
                    depth
                    > MAX_JSON_DEPTH
            ) {
                fail();
            }

            if (
                    index
                    >= input.length()
            ) {
                fail();
            }

            char current =
                    input.charAt(
                            index
                    );

            switch (current) {
                case '{' ->
                        parseObject(
                                depth
                        );

                case '[' ->
                        parseArray(
                                depth
                        );

                case '"' ->
                        parseString();

                case 't' ->
                        parseLiteral(
                                "true"
                        );

                case 'f' ->
                        parseLiteral(
                                "false"
                        );

                case 'n' ->
                        parseLiteral(
                                "null"
                        );

                default -> {
                    if (
                            current == '-'
                            || isDigit(
                                    current
                            )
                    ) {
                        parseNumber();
                        return;
                    }

                    fail();
                }
            }
        }

        private void parseObject(
                int depth
        ) {
            expect(
                    '{'
            );

            skipWhitespace();

            if (consume('}')) {
                return;
            }

            while (true) {
                if (
                        index
                                >= input.length()
                        || input.charAt(index)
                                != '"'
                ) {
                    fail();
                }

                parseString();

                skipWhitespace();

                expect(
                        ':'
                );

                skipWhitespace();

                parseValue(
                        depth + 1
                );

                skipWhitespace();

                if (consume('}')) {
                    return;
                }

                expect(
                        ','
                );

                skipWhitespace();
            }
        }

        private void parseArray(
                int depth
        ) {
            expect(
                    '['
            );

            skipWhitespace();

            if (consume(']')) {
                return;
            }

            while (true) {
                parseValue(
                        depth + 1
                );

                skipWhitespace();

                if (consume(']')) {
                    return;
                }

                expect(
                        ','
                );

                skipWhitespace();
            }
        }

        private void parseString() {
            expect(
                    '"'
            );

            while (
                    index
                    < input.length()
            ) {
                char current =
                        input.charAt(
                                index++
                        );

                if (current == '"') {
                    return;
                }

                if (current == '\\') {
                    parseEscape();
                    continue;
                }

                if (current <= 0x1F) {
                    fail();
                }
            }

            fail();
        }

        private void parseEscape() {
            if (
                    index
                    >= input.length()
            ) {
                fail();
            }

            char escaped =
                    input.charAt(
                            index++
                    );

            if (escaped == 'u') {
                parseUnicodeEscape();
            } else if (
                    !isSimpleEscape(
                            escaped
                    )
            ) {
                fail();
            }
        }

        private void parseUnicodeEscape() {
            for (
                    int i = 0;
                    i < 4;
                    i++
            ) {
                if (
                        index
                        >= input.length()
                        || !isHex(
                                input.charAt(
                                        index++
                                )
                        )
                ) {
                    fail();
                }
            }
        }

        private static boolean isSimpleEscape(
                char value
        ) {
            return value == '"'
                    || value == '\\'
                    || value == '/'
                    || value == 'b'
                    || value == 'f'
                    || value == 'n'
                    || value == 'r'
                    || value == 't';
        }

        private void parseLiteral(
                String literal
        ) {
            if (
                    !input.startsWith(
                            literal,
                            index
                    )
            ) {
                fail();
            }

            index +=
                    literal.length();
        }

        private void parseNumber() {
            if (consume('-')) {
                if (
                        index
                        >= input.length()
                ) {
                    fail();
                }
            }

            if (consume('0')) {
                if (
                        index
                                < input.length()
                        && isDigit(
                                input.charAt(
                                        index
                                )
                        )
                ) {
                    fail();
                }
            } else {
                requireDigitOneToNine();

                while (
                        index
                                < input.length()
                        && isDigit(
                                input.charAt(
                                        index
                                )
                        )
                ) {
                    index++;
                }
            }

            if (consume('.')) {
                requireDigit();

                while (
                        index
                                < input.length()
                        && isDigit(
                                input.charAt(
                                        index
                                )
                        )
                ) {
                    index++;
                }
            }

            if (
                    consume('e')
                    || consume('E')
            ) {
                consumeOptionalSign();

                requireDigit();

                while (
                        index
                                < input.length()
                        && isDigit(
                                input.charAt(
                                        index
                                )
                        )
                ) {
                    index++;
                }
            }
        }

        private void consumeOptionalSign() {
            if (
                    index
                    >= input.length()
            ) {
                return;
            }

            char current =
                    input.charAt(
                            index
                    );

            if (
                    current == '+'
                    || current == '-'
            ) {
                index++;
            }
        }

        private void requireDigitOneToNine() {
            if (
                    index
                    >= input.length()
            ) {
                fail();
            }

            char current =
                    input.charAt(
                            index
                    );

            if (
                    current < '1'
                    || current > '9'
            ) {
                fail();
            }

            index++;
        }

        private void requireDigit() {
            if (
                    index
                    >= input.length()
                    || !isDigit(
                            input.charAt(
                                    index
                            )
                    )
            ) {
                fail();
            }

            index++;
        }

        private void skipWhitespace() {
            while (
                    index
                    < input.length()
            ) {
                char current =
                        input.charAt(
                                index
                        );

                if (
                        current != ' '
                        && current != '\t'
                        && current != '\r'
                        && current != '\n'
                ) {
                    return;
                }

                index++;
            }
        }

        private boolean consume(
                char expected
        ) {
            if (
                    index
                            < input.length()
                    && input.charAt(index)
                            == expected
            ) {
                index++;
                return true;
            }

            return false;
        }

        private void expect(
                char expected
        ) {
            if (!consume(expected)) {
                fail();
            }
        }

        private static boolean isDigit(
                char value
        ) {
            return value >= '0'
                    && value <= '9';
        }

        private static boolean isHex(
                char value
        ) {
            return value >= '0'
                    && value <= '9'
                    || value >= 'a'
                    && value <= 'f'
                    || value >= 'A'
                    && value <= 'F';
        }

        private static void fail() {
            throw invalidStructuredFormat(
                    "JSON"
            );
        }
    }
}