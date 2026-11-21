// backend/src/main/java/ru/safeai/gateway/usage/dto/UsageProviderReportResponse.java
package ru.safeai.gateway.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

public record UsageProviderReportResponse(String from, String to,
        @JsonInclude(JsonInclude.Include.ALWAYS) UUID organizationId,
        @JsonInclude(JsonInclude.Include.ALWAYS) String model,
        @JsonInclude(JsonInclude.Include.ALWAYS) UUID userId, String aggregationBasis,
        List<UsageProviderRow> rows) {}
