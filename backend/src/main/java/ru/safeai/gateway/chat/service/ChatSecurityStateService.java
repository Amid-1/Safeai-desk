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

    public void assertStillActive(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            SafeAiUserPrincipal currentUser
    ) {
        if (!repository.isActive(
                currentUser.getId(),
                currentUser.getOrganizationId(),
                currentUser.getTokenVersion(),
                currentUser.getOrganizationAuthVersion()
        )) {
            throw new ChatAccessRevokedException(
                    chatId,
                    turnId,
                    clientRequestId
            );
        }
    }
}
