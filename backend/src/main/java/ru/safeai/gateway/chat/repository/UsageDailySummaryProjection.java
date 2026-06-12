package ru.safeai.gateway.chat.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface UsageDailySummaryProjection {

    LocalDate getUsageDate();

    Long getInputTokens();

    Long getOutputTokens();

    BigDecimal getCostUsd();
}