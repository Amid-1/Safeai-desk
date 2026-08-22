package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;

@Component
public class ChatContentNormalizer {

    private final ChatProperties properties;

    public ChatContentNormalizer(ChatProperties properties) {
        this.properties = properties;
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

        if (normalized.length() > properties.maxMessageChars()) {
            throw new BadRequestException(
                    "Сообщение не должно превышать "
                            + properties.maxMessageChars()
                            + " символов"
            );
        }

        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '\n' || character == '\t') {
                continue;
            }
            if (Character.isISOControl(character)) {
                throw new BadRequestException(
                        "Сообщение содержит недопустимый управляющий символ"
                );
            }
        }

        return normalized;
    }

    public String sha256(String normalizedContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    normalizedContent.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }

    public String requestHash(
            String normalizedContent,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        KnowledgeMode mode = knowledgeMode == null
                ? KnowledgeMode.GENERAL
                : knowledgeMode;
        String canonical = normalizedContent
                + "\n\u001fknowledge-mode=" + mode.name()
                + "\n\u001fknowledge-base="
                + (knowledgeBaseId == null ? "-" : knowledgeBaseId);
        return sha256(canonical);
    }
}
