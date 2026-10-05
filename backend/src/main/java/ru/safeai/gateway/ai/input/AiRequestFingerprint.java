package ru.safeai.gateway.ai.input;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Canonical collision-safe SHA-256 fingerprints for AI requests.
 *
 * <p>The base fingerprint deliberately excludes mutable execution-envelope
 * metadata and identifies the exact pre-RAG textual request. The prepared
 * fingerprint additionally binds tenant/user/chat/provider-operation identity
 * and the governed input/output caps. Both encodings use UTF-8 byte-length
 * prefixes, so concatenation boundaries are unambiguous.</p>
 */
public final class AiRequestFingerprint {

    private AiRequestFingerprint() {
    }

    public static String baseSha256(AiChatRequest request) {
        Objects.requireNonNull(request, "request");
        return digest(out -> {
            write(out, request.userMessage());
            write(out, request.systemInstructions());
            write(out, request.developerInstructions());
            writeHistory(out, request);
        });
    }

    public static String preparedSha256(AiChatRequest request) {
        Objects.requireNonNull(request, "request");
        return digest(out -> {
            write(out, request.organizationId() == null ? null : request.organizationId().toString());
            write(out, request.userId() == null ? null : request.userId().toString());
            write(out, request.chatId() == null ? null : request.chatId().toString());
            write(out, request.providerOperationId() == null
                    ? null : request.providerOperationId().toString());
            write(out, request.reservedInputTokens() == null
                    ? null : request.reservedInputTokens().toString());
            write(out, request.maxOutputTokens() == null
                    ? null : request.maxOutputTokens().toString());
            write(out, request.userMessage());
            write(out, request.systemInstructions());
            write(out, request.developerInstructions());
            writeHistory(out, request);
        });
    }

    private static void writeHistory(DataOutputStream out, AiChatRequest request)
            throws IOException {
        out.writeInt(request.history().size());
        for (AiMessage message : request.history()) {
            write(out, message.role().name());
            write(out, message.content());
        }
    }

    private static String digest(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writer.write(out);
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            );
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash AI request", exception);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
