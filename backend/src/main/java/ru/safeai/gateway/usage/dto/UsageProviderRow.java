// backend/src/main/java/ru/safeai/gateway/usage/dto/UsageProviderRow.java
package ru.safeai.gateway.usage.dto;

import java.math.BigDecimal;

/** Точные строки вместо JSON number; неизвестные расходы не заменяются нулём. */
public record UsageProviderRow(String provider, String attempts, String unresolved,
                               String unknownCost, String unknownUsage, String input,
                               String output, String knownCostUsd) {
    public UsageProviderRow {
        if (provider == null || provider.isBlank() || provider.length() > 32
                || provider.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Некорректный провайдер");
        }
        long total = Long.parseLong(attempts);
        long pending = Long.parseLong(unresolved);
        long costUnknown = Long.parseLong(unknownCost);
        long tokensUnknown = Long.parseLong(unknownUsage);
        if (total <= 0 || pending < 0 || costUnknown < pending || tokensUnknown < pending
                || costUnknown > total || tokensUnknown > total || Long.parseLong(input) < 0
                || Long.parseLong(output) < 0 || new BigDecimal(knownCostUsd).signum() < 0) {
            throw new IllegalArgumentException("Несогласованные данные провайдера");
        }
    }
}
