package ru.safeai.gateway.audit.dto;

import org.springframework.format.annotation.DateTimeFormat;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public record AuditEventFilter(
        AuditEventType eventType,
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
        public AuditEventFilter {
                userEmail = normalizeEmail(userEmail);

                if (dateFrom != null
                        && dateTo != null
                        && !dateFrom.isBefore(dateTo)) {
                        throw new BadRequestException(
                                "dateFrom должен быть раньше dateTo"
                        );
                }
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