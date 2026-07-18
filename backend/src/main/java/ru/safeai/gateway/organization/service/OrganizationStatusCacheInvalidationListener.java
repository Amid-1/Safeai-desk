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

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrganizationStatusCacheInvalidationListener {

    private static final int BATCH_SIZE = 1_000;

    private final UserRepository userRepository;
    private final UserStatusCacheService userStatusCacheService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW
    )
    public void onOrganizationSecurityStateChanged(
            OrganizationSecurityStateChangedEvent event
    ) {
        try {
            List<UUID> userIds =
                    userRepository.findIdsByOrganizationId(
                            event.organizationId()
                    );

            for (int from = 0; from < userIds.size(); from += BATCH_SIZE) {
                int to = Math.min(from + BATCH_SIZE, userIds.size());

                userStatusCacheService.evictAll(
                        userIds.subList(from, to)
                );
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to evict user status cache for organization: "
                            + "organizationId={}",
                    event.organizationId(),
                    exception
            );
        }
    }
}
