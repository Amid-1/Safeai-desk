package ru.safeai.gateway.organization.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.safeai.gateway.organization.event
        .OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrganizationStatusCacheInvalidationListener {

    private static final int PAGE_SIZE = 1_000;

    private final UserRepository userRepository;
    private final UserStatusCacheService userStatusCacheService;

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW
    )
    public void onOrganizationSecurityStateChanged(
            OrganizationSecurityStateChangedEvent event
    ) {
        int pageNumber = 0;

        try {
            Slice<UUID> page;

            do {
                page = userRepository.findIdsByOrganizationId(
                        event.organizationId(),
                        PageRequest.of(
                                pageNumber,
                                PAGE_SIZE
                        )
                );

                if (!page.isEmpty()) {
                    userStatusCacheService.evictAll(
                            page.getContent()
                    );
                }

                pageNumber++;
            } while (page.hasNext());
        } catch (RuntimeException exception) {
            log.error(
                    "Organization status-cache eviction failed: "
                            + "organizationId={}, authVersion={}",
                    event.organizationId(),
                    event.authVersion(),
                    exception
            );
        }
    }
}