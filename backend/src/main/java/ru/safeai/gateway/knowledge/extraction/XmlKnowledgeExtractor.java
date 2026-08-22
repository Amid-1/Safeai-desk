package ru.safeai.gateway.knowledge.extraction;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Component
public class XmlKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION = "stax-xml-v1";

    private final KnowledgeIngestionProperties properties;

    public XmlKnowledgeExtractor(KnowledgeIngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return "application/xml".equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        String source = ExtractionTextSupport.decodeUtf8(content, "XML-файл");
        XMLStreamReader reader = null;
        try {
            reader = secureFactory().createXMLStreamReader(
                    new StringReader(source)
            );
            ExtractionState state = extract(reader);
            String text = ExtractionTextSupport.normalize(
                    state.output().isEmpty()
                            ? state.rootElement()
                            : state.output()
            );
            return new ExtractedDocument(
                    VERSION,
                    List.of(new ExtractedSection(
                            null,
                            state.rootElement(),
                            text
                    )),
                    text.length()
            );
        } catch (XMLStreamException exception) {
            throw new KnowledgeIngestionException(
                    "INVALID_XML",
                    "Не удалось безопасно разобрать XML",
                    false,
                    exception
            );
        } finally {
            closeQuietly(reader);
        }
    }

    private ExtractionState extract(XMLStreamReader reader)
            throws XMLStreamException {
        Deque<ElementState> elements = new ArrayDeque<>();
        StringBuilder output = new StringBuilder();
        String rootElement = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.DTD
                    || event == XMLStreamConstants.ENTITY_REFERENCE) {
                throw new KnowledgeIngestionException(
                        "UNSAFE_XML",
                        "DTD и XML entities запрещены",
                        false
                );
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                elements.addLast(new ElementState(elementName));
                if (rootElement == null) {
                    rootElement = elementName;
                }
                appendAttributes(reader, elements, output);
            } else if ((event == XMLStreamConstants.CHARACTERS
                    || event == XMLStreamConstants.CDATA)
                    && !elements.isEmpty()) {
                elements.getLast().text().append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && !elements.isEmpty()) {
                ElementState element = elements.removeLast();
                String value = ExtractionTextSupport.normalize(
                        element.text().toString()
                );
                if (!value.isBlank()) {
                    appendLine(
                            output,
                            path(elements, element.name()),
                            value
                    );
                }
            }
        }

        if (rootElement == null) {
            throw new KnowledgeIngestionException(
                    "INVALID_XML",
                    "XML не содержит корневого элемента",
                    false
            );
        }
        String normalized = ExtractionTextSupport.normalize(output.toString());
        ExtractionTextSupport.addAndCheck(
                0,
                normalized,
                properties.maxExtractedChars()
        );
        return new ExtractionState(rootElement, normalized);
    }

    private void appendAttributes(
            XMLStreamReader reader,
            Deque<ElementState> elements,
            StringBuilder output
    ) {
        String elementPath = path(elements, null);
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            appendLine(
                    output,
                    elementPath + ".@" + reader.getAttributeLocalName(index),
                    reader.getAttributeValue(index)
            );
            if (output.length() > properties.maxExtractedChars()) {
                throw ExtractionTextSupport.tooLarge();
            }
        }
    }

    private static void appendLine(
            StringBuilder output,
            String path,
            String value
    ) {
        if (!output.isEmpty()) {
            output.append('\n');
        }
        output.append(path).append(": ").append(value);
    }

    private static String path(
            Deque<ElementState> elements,
            String terminal
    ) {
        StringBuilder result = new StringBuilder();
        for (ElementState element : elements) {
            if (!result.isEmpty()) {
                result.append('.');
            }
            result.append(element.name());
        }
        if (terminal != null) {
            if (!result.isEmpty()) {
                result.append('.');
            }
            result.append(terminal);
        }
        return result.toString();
    }

    private static XMLInputFactory secureFactory() {
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

    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // Результат extraction уже определён.
        }
    }

    private record ElementState(String name, StringBuilder text) {

        private ElementState(String name) {
            this(name, new StringBuilder());
        }
    }

    private record ExtractionState(String rootElement, String output) {
    }
}
