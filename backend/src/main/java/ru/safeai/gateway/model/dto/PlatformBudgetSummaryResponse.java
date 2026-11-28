package ru.safeai.gateway.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/** Mutually exclusive categories for the current UTC month, including reservations. */
public record PlatformBudgetSummaryResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant periodEnd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant asOf,
        long total, long enabled, long within, long nearLimit, long exceeded,
        long noLimit, long unknown, long disabled, long unconfigured
) {}
