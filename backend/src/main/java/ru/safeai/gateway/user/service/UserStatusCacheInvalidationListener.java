package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.safeai.gateway.user.event.UserSecurityStateChangedEvent;

@Component
@RequiredArgsConstructor
public class UserStatusCacheInvalidationListener {

    private final UserStatusCacheService userStatusCacheService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserSecurityStateChanged(
            UserSecurityStateChangedEvent event
    ) {
        userStatusCacheService.evict(event.userId());
    }
}
