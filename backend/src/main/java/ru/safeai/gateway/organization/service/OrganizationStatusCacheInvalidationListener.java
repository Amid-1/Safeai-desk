package ru.safeai.gateway.organization.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.service.UserStatusCacheService;

@Slf4j
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
        try {
            userRepository.findIdsByOrganizationId(event.organizationId())
                    .forEach(userStatusCacheService::evict);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to evict user status cache for organization: organizationId={}",
                    event.organizationId(),
                    exception
            );
        }
    }
}