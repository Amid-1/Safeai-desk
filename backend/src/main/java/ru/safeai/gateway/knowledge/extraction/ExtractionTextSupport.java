package ru.safeai.gateway.knowledge.extraction;

import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class ExtractionTextSupport {

    private ExtractionTextSupport() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0000', ' ')
                .lines()
                .map(String::stripTrailing)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .strip();
    }

    static String decodeUtf8(byte[] content, String format) {
        try {
            String value = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            return !value.isEmpty() && value.charAt(0) == '\ufeff'
                    ? value.substring(1)
                    : value;
        } catch (CharacterCodingException exception) {
            throw new KnowledgeIngestionException(
                    "INVALID_UTF8",
                    format + " должен быть корректным UTF-8",
                    false,
                    exception
            );
        }
    }

    static int addAndCheck(
            int current,
            String text,
            int maximum
    ) {
        int updated;
        try {
            updated = Math.addExact(current, text.length());
        } catch (ArithmeticException exception) {
            throw tooLarge();
        }
        if (updated > maximum) {
            throw tooLarge();
        }
        return updated;
    }

    static KnowledgeIngestionException tooLarge() {
        return new KnowledgeIngestionException(
                "EXTRACTED_TEXT_TOO_LARGE",
                "Извлечённый текст превышает безопасный лимит",
                false
        );
    }
}
