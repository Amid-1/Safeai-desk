package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.chat.exception.ChatAccessRevokedException;
import ru.safeai.gateway.chat.repository.ChatSecurityStateRepository;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

@Service
public class ChatSecurityStateService {

    private final ChatSecurityStateRepository repository;

    public ChatSecurityStateService(ChatSecurityStateRepository repository) {
        this.repository = repository;
    }

    /**
     * Response is persisted first for accounting. This method controls only
     * whether it may still be returned to a caller whose access was revoked
     * during the provider call.
     */
    public void assertStillActive(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            SafeAiUserPrincipal currentUser
    ) {
        if (!repository.isActive(
                currentUser.getId(),
                currentUser.getOrganizationId()
        )) {
            throw new ChatAccessRevokedException(
                    chatId,
                    turnId,
                    clientRequestId
            );
        }
    }
}
