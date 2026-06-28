package ru.safeai.gateway.organization.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.service.UserStatusCacheService;

@Component
@RequiredArgsConstructor
public class OrganizationStatusCacheInvalidationListener {

    private final UserRepository userRepository;
    private final UserStatusCacheService userStatusCacheService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void onOrganizationSecurityStateChanged(
            OrganizationSecurityStateChangedEvent event
    ) {
        userRepository.findIdsByOrganizationId(event.organizationId())
                .forEach(userStatusCacheService::evict);
    }
}