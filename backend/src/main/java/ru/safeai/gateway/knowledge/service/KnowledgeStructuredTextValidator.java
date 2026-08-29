package ru.safeai.gateway.knowledge.service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class KnowledgeStructuredTextValidator {

    private static final int MAX_JSON_DEPTH = 128;

    private KnowledgeStructuredTextValidator() {
    }

    static String decodeStrictUtf8(
            byte[] bytes
    ) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw KnowledgeValidationErrors.unsupportedType();
        }
    }

    static void validateTextControls(
            String value
    ) {
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\r'
                    && codePoint != '\t') {
                throw KnowledgeValidationErrors.unsupportedType();
            }
        });
    }

    static boolean looksLikeHtml(
            String value
    ) {
        String normalizedHead = stripUtf8Bom(value)
                .stripLeading()
                .toLowerCase(Locale.ROOT);

        return normalizedHead.startsWith("<!doctype html")
                || normalizedHead.startsWith("<html");
    }

    static void validateJson(
            String value
    ) {
        new StrictJsonParser(stripUtf8Bom(value)).parseDocument();
    }

    static void validateXml(
            String value
    ) {
        String xml = stripUtf8Bom(value);

        if (containsForbiddenXmlDeclaration(xml)) {
            throw KnowledgeValidationErrors.invalidStructuredFormat("XML");
        }

        XMLInputFactory factory = secureXmlInputFactory();
        XMLStreamReader reader = null;
        boolean rootElementFound = false;

        try {
            reader = factory.createXMLStreamReader(new StringReader(xml));
            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.DTD
                        || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw KnowledgeValidationErrors.invalidStructuredFormat("XML");
                }

                if (event == XMLStreamConstants.START_ELEMENT) {
                    rootElementFound = true;
                }
            }
        } catch (XMLStreamException exception) {
            throw KnowledgeValidationErrors.invalidStructuredFormat("XML");
        } finally {
            closeQuietly(reader);
        }

        if (!rootElementFound) {
            throw KnowledgeValidationErrors.invalidStructuredFormat("XML");
        }
    }

    static boolean containsForbiddenXmlDeclaration(
            String value
    ) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("<!doctype")
                || normalized.contains("<!entity");
    }

    static XMLInputFactory secureXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
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

    static void closeQuietly(
            XMLStreamReader reader
    ) {
        if (reader == null) {
            return;
        }

        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // Validation result is already determined.
        }
    }

    static String stripUtf8Bom(
            String value
    ) {
        return !value.isEmpty() && value.charAt(0) == '\uFEFF'
                ? value.substring(1)
                : value;
    }

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

            if (index != input.length()) {
                fail();
            }
        }

        private void parseValue(
                int depth
        ) {
            if (depth > MAX_JSON_DEPTH
                    || index >= input.length()) {
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
                    if (current == '-'
                            || isDigit(
                            current
                    )) {
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

            if (consume(
                    '}'
            )) {
                return;
            }

            while (true) {
                if (index >= input.length()
                        || input.charAt(
                        index
                ) != '"') {
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

                if (consume(
                        '}'
                )) {
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

            if (consume(
                    ']'
            )) {
                return;
            }

            while (true) {
                parseValue(
                        depth + 1
                );

                skipWhitespace();

                if (consume(
                        ']'
                )) {
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

            while (index < input.length()) {
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
            if (index >= input.length()) {
                fail();
            }

            char escaped =
                    input.charAt(
                            index++
                    );

            if (escaped == 'u') {
                parseUnicodeEscape();
                return;
            }

            if (!isSimpleEscape(
                    escaped
            )) {
                fail();
            }
        }

        private void parseUnicodeEscape() {
            for (
                    int count = 0;
                    count < 4;
                    count++
            ) {
                if (index >= input.length()
                        || !isHex(
                        input.charAt(
                                index++
                        )
                )) {
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
            if (!input.startsWith(
                    literal,
                    index
            )) {
                fail();
            }

            index +=
                    literal.length();
        }

        private void parseNumber() {
            if (consume(
                    '-'
            )
                    && index >= input.length()) {
                fail();
            }

            if (consume(
                    '0'
            )) {
                if (index < input.length()
                        && isDigit(
                        input.charAt(
                                index
                        )
                )) {
                    fail();
                }
            } else {
                requireDigitOneToNine();

                while (index < input.length()
                        && isDigit(
                        input.charAt(
                                index
                        )
                )) {
                    index++;
                }
            }

            if (consume(
                    '.'
            )) {
                requireDigit();

                while (index < input.length()
                        && isDigit(
                        input.charAt(
                                index
                        )
                )) {
                    index++;
                }
            }

            if (consume(
                    'e'
            )
                    || consume(
                    'E'
            )) {
                if (index < input.length()
                        && (
                        input.charAt(index) == '+'
                                || input.charAt(index) == '-'
                )) {
                    index++;
                }

                requireDigit();

                while (index < input.length()
                        && isDigit(
                        input.charAt(
                                index
                        )
                )) {
                    index++;
                }
            }
        }

        private void requireDigitOneToNine() {
            if (index >= input.length()) {
                fail();
            }

            char current =
                    input.charAt(
                            index
                    );

            if (current < '1'
                    || current > '9') {
                fail();
            }

            index++;
        }

        private void requireDigit() {
            if (index >= input.length()
                    || !isDigit(
                    input.charAt(
                            index
                    )
            )) {
                fail();
            }

            index++;
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char current =
                        input.charAt(
                                index
                        );

                if (current != ' '
                        && current != '\t'
                        && current != '\r'
                        && current != '\n') {
                    return;
                }

                index++;
            }
        }

        private boolean consume(
                char expected
        ) {
            if (index < input.length()
                    && input.charAt(
                    index
            ) == expected) {
                index++;
                return true;
            }

            return false;
        }

        private void expect(
                char expected
        ) {
            if (!consume(
                    expected
            )) {
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
            throw KnowledgeValidationErrors.invalidStructuredFormat(
                    "JSON"
            );
        }
    }
}
