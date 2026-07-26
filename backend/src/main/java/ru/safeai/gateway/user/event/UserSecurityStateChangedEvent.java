package ru.safeai.gateway.user.event;

import java.util.UUID;

public record UserSecurityStateChangedEvent(
        UUID userId
) {
}
