package ru.safeai.gateway.usage.dto;

import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

public record UsageDateModelFilter(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dateFrom,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dateTo,

        @Size(max = 100)
        String model
) {
    public String normalizedModel() {
        return model == null || model.isBlank()
                ? null
                : model.trim();
    }
}
