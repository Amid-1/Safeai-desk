// backend/src/main/java/ru/safeai/gateway/usage/dto/UsageExportRequest.java
package ru.safeai.gateway.usage.dto;

import ru.safeai.gateway.common.budget.PlatformBudgetClassification.Status;

import java.time.Instant;
import java.util.UUID;

public record UsageExportRequest(Kind kind, Instant dateFrom, Instant dateTo, UUID organizationId,
                                 UUID userId, String model, String order, Status budgetStatus) {
    public enum Kind { SUMMARY, USERS, MODELS, DAILY, ORGANIZATIONS, PROVIDERS }
}
