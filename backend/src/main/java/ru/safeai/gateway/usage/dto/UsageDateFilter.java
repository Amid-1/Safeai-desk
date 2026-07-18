package ru.safeai.gateway.usage.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

public record UsageDateFilter(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dateFrom,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dateTo
) {
}
