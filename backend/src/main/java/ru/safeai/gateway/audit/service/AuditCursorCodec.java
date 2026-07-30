package ru.safeai.gateway.audit.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class AuditCursorCodec {

    private static final String VERSION = "v1";

    public String encode(
            Instant createdAt,
            UUID id
    ) {
        String raw = VERSION
                + "|"
                + createdAt
                + "|"
                + id;

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        raw.getBytes(StandardCharsets.UTF_8)
                );
    }

    public AuditCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(
                            cursor.trim()
                    ),
                    StandardCharsets.UTF_8
            );

            String[] parts = raw.split("\\|", -1);

            if (parts.length != 3
                    || !VERSION.equals(parts[0])) {
                throw invalidCursor();
            }

            return new AuditCursor(
                    Instant.parse(parts[1]),
                    UUID.fromString(parts[2])
            );
        } catch (RuntimeException exception) {
            if (exception instanceof BadRequestException) {
                throw exception;
            }

            throw invalidCursor();
        }
    }

    private BadRequestException invalidCursor() {
        return new BadRequestException(
                "Некорректный audit cursor"
        );
    }

    public record AuditCursor(
            Instant createdAt,
            UUID id
    ) {
    }
}
