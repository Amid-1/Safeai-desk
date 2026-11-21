package ru.safeai.gateway.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatErasureOperationResponse(
        UUID operationId, UUID chatId, String state, Instant createdAt,
        Instant completedAt, String lastErrorCode
) { }
