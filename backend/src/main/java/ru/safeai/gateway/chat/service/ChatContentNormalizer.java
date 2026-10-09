package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Component
public class ChatContentNormalizer {

    private final ChatProperties properties;

    public ChatContentNormalizer(
            ChatProperties properties
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
    }

    public String normalize(String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException(
                    "Сообщение не должно быть пустым"
            );
        }

        String normalized = content
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        if (normalized.length()
                > properties.maxMessageChars()) {
            throw new BadRequestException(
                    "Сообщение не должно превышать "
                            + properties.maxMessageChars()
                            + " UTF-16 code units"
            );
        }

        validateUnicode(normalized);
        validateControls(normalized);

        return normalized;
    }

    public String sha256(
            String normalizedContent
    ) {
        Objects.requireNonNull(
                normalizedContent,
                "normalizedContent не должен быть null"
        );

        byte[] bytes = strictUtf8(
                normalizedContent
        );

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 недоступен",
                    exception
            );
        }
    }

    public String requestHash(
            String normalizedContent,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        KnowledgeMode mode =
                knowledgeMode == null
                        ? KnowledgeMode.GENERAL
                        : knowledgeMode;

        String canonical =
                normalizedContent
                        + "\n\u001fknowledge-mode="
                        + mode.name()
                        + "\n\u001fknowledge-base="
                        + (knowledgeBaseId == null
                        ? "-"
                        : knowledgeBaseId);

        return sha256(canonical);
    }

    private static void validateControls(
            String value
    ) {
        for (int index = 0;
             index < value.length();
             index++) {

            char character =
                    value.charAt(index);

            if (character == '\n'
                    || character == '\t') {
                continue;
            }

            if (Character.isISOControl(character)) {
                throw new BadRequestException(
                        "Сообщение содержит недопустимый управляющий символ"
                );
            }
        }
    }

    private static void validateUnicode(
            String value
    ) {
        for (int index = 0;
             index < value.length();
             index++) {

            char current =
                    value.charAt(index);

            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(
                        value.charAt(index + 1)
                )) {
                    throw new BadRequestException(
                            "Сообщение содержит некорректную Unicode-последовательность"
                    );
                }

                index++;
                continue;
            }

            if (Character.isLowSurrogate(current)) {
                throw new BadRequestException(
                        "Сообщение содержит некорректную Unicode-последовательность"
                );
            }
        }
    }

    private static byte[] strictUtf8(
            String value
    ) {
        try {
            ByteBuffer buffer =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(
                                    CodingErrorAction.REPORT
                            )
                            .onUnmappableCharacter(
                                    CodingErrorAction.REPORT
                            )
                            .encode(
                                    CharBuffer.wrap(value)
                            );

            byte[] result =
                    new byte[buffer.remaining()];

            buffer.get(result);

            return result;
        } catch (CharacterCodingException exception) {
            throw new BadRequestException(
                    "Сообщение содержит некорректную Unicode-последовательность"
            );
        }
    }
}
