package ru.safeai.gateway.usage.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface UsageDailySummaryProjection {

    LocalDate getUsageDate();

    Long getInputTokens();

    Long getOutputTokens();

    BigDecimal getCostUsd();
}