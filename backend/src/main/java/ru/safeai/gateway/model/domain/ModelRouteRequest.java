package ru.safeai.gateway.model.domain;

import ru.safeai.gateway.ai.dto.AiMessage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable input to the deterministic V45 governance decision. */
public record ModelRouteRequest(
        UUID organizationId,
        UUID userId,
        UUID chatId,
        UUID plannedTurnId,
        UUID clientRequestId,
        String requestContentHash,
        String requestedModelKey,
        String userMessage,
        List<AiMessage> history,
        Set<ModelCapability> requiredCapabilities
) {
    public ModelRouteRequest {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );
        Objects.requireNonNull(
                plannedTurnId,
                "plannedTurnId не должен быть null"
        );
        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );
        Objects.requireNonNull(
                requestContentHash,
                "requestContentHash не должен быть null"
        );
        Objects.requireNonNull(
                userMessage,
                "userMessage не должен быть null"
        );

        history = history == null
                ? List.of()
                : List.copyOf(history);

        if (requiredCapabilities == null
                || requiredCapabilities.isEmpty()) {
            requiredCapabilities = Set.of();
        } else {
            EnumSet<ModelCapability> copy =
                    EnumSet.noneOf(ModelCapability.class);
            copy.addAll(requiredCapabilities);
            requiredCapabilities =
                    Collections.unmodifiableSet(copy);
        }
    }
}
