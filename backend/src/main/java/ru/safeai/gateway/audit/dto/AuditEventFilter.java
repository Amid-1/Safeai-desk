package ru.safeai.gateway.audit.dto;

import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public record AuditEventFilter(
        AuditEventType eventType,

        @Size(max = 320)
        String userEmail,

        UUID userId,

        @DateTimeFormat(
                iso = DateTimeFormat.ISO.DATE_TIME
        )
        Instant dateFrom,

        @DateTimeFormat(
                iso = DateTimeFormat.ISO.DATE_TIME
        )
        Instant dateTo,

        UUID organizationId
) {
    private static final int MAX_USER_EMAIL_FILTER_LENGTH = 320;

    public AuditEventFilter {
        userEmail = normalizeEmail(userEmail);

        if (userEmail != null
                && userEmail.length()
                > MAX_USER_EMAIL_FILTER_LENGTH) {
            throw new BadRequestException(
                    "Email инициатора или префикс должен быть "
                            + "не длиннее "
                            + MAX_USER_EMAIL_FILTER_LENGTH
                            + " символов"
            );
        }

        if (userEmail != null
                && containsControlCharacters(userEmail)) {
            throw new BadRequestException(
                    "Email инициатора содержит управляющие символы"
            );
        }

        if (dateFrom != null
                && dateTo != null
                && !dateFrom.isBefore(dateTo)) {
            throw new BadRequestException(
                    "dateFrom должен быть раньше dateTo"
            );
        }
    }

    private static boolean containsControlCharacters(
            String value
    ) {
        return value.chars().anyMatch(character ->
                character <= 0x1f || character == 0x7f
        );
    }

    private static String normalizeEmail(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
