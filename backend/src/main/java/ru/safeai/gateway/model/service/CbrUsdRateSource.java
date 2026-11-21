package ru.safeai.gateway.model.service;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** Fixed, official HTTPS endpoint. Never transmits tenant data or credentials. */
final class CbrUsdRateSource {

    private static final int MAX_BYTES = 128 * 1024;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.uuuu")
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter REQUEST_DATE =
            DateTimeFormatter.ofPattern("dd/MM/uuuu");

    record Rate(
            LocalDate effectiveDate,
            BigDecimal rubPerUsd
    ) {}

    Rate fetch(LocalDate date) throws IOException {
        var url = URI.create(
                "https://www.cbr.ru/scripts/XML_daily.asp?date_req="
                        + REQUEST_DATE.format(date)
        ).toURL();

        var connection = (HttpsURLConnection) url.openConnection();

        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/xml, text/xml");
        connection.setRequestProperty("Accept-Encoding", "identity");

        try {
            if (connection.getResponseCode() != 200
                    || connection.getContentLengthLong() > MAX_BYTES) {
                throw new IOException("Unexpected CBR response");
            }

            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);

                if (bytes.length > MAX_BYTES) {
                    throw new IOException("CBR response too large");
                }

                return parse(bytes, date);
            }
        } finally {
            connection.disconnect();
        }
    }

    static Rate parse(
            byte[] bytes,
            LocalDate requested
    ) throws IOException {

        if (bytes.length > MAX_BYTES) {
            throw new IOException("CBR response too large");
        }

        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();

            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            var builder = factory.newDocumentBuilder();

            // No raw external document content in application logs.
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }
            });

            Element root = builder
                    .parse(new ByteArrayInputStream(bytes))
                    .getDocumentElement();

            if (!"ValCurs".equals(root.getTagName())) {
                throw new IOException("Unexpected CBR document");
            }

            LocalDate effective = LocalDate.parse(
                    root.getAttribute("Date"),
                    DATE
            );

            if (effective.isAfter(requested)
                    || effective.isBefore(requested.minusDays(14))) {
                throw new IOException("Unexpected CBR effective date");
            }

            NodeList currencies = root.getElementsByTagName("Valute");

            Rate found = null;

            for (int i = 0; i < currencies.getLength(); i++) {
                if (!(currencies.item(i) instanceof Element item)) {
                    continue;
                }

                if (!"USD".equals(field(item, "CharCode"))) {
                    continue;
                }

                if (found != null
                        || !"840".equals(field(item, "NumCode"))
                        || !"1".equals(field(item, "Nominal"))) {
                    throw new IOException("Unexpected USD definition");
                }

                String raw = field(item, "Value");

                if (!raw.matches("[0-9]{1,10},[0-9]{1,8}")) {
                    throw new IOException("Invalid USD rate");
                }

                BigDecimal rate = new BigDecimal(raw.replace(',', '.'));

                if (rate.signum() <= 0) {
                    throw new IOException("Invalid USD rate");
                }

                found = new Rate(effective, rate);
            }

            if (found == null) {
                throw new IOException("USD rate is missing");
            }

            return found;

        } catch (ParserConfigurationException | SAXException | RuntimeException failure) {
            throw new IOException("Invalid CBR rate document", failure);
        }
    }

    private static String field(
            Element element,
            String name
    ) throws IOException {

        NodeList fields = element.getElementsByTagName(name);

        if (fields.getLength() != 1) {
            throw new IOException("Missing CBR currency field");
        }

        return fields.item(0).getTextContent().trim();
    }
}
