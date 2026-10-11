package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Objects;
import java.util.UUID;

/** Optional after-commit invalidation. Never participates in security correctness. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrganizationStatusCacheInvalidationListener {
    private static final int PAGE_SIZE = 1_000;

    private final UserRepository userRepository;
    private final UserStatusCacheService userStatusCacheService;
    private final UserStatusCacheProperties cacheProperties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void onOrganizationSecurityStateChanged(OrganizationSecurityStateChangedEvent event) {
        Objects.requireNonNull(event, "event не должен быть null");

        // In production the cache is disabled. Avoid a full tenant scan for no work.
        if (!cacheProperties.isEnabled()) {
            return;
        }

        int pageNumber = 0;
        try {
            Slice<UUID> slice;
            do {
                slice = userRepository.findIdsByOrganizationId(
                        event.organizationId(),
                        PageRequest.of(pageNumber, PAGE_SIZE, Sort.by(Sort.Order.asc("id"))));
                if (!slice.isEmpty()) {
                    userStatusCacheService.evictAll(slice.getContent());
                }
                pageNumber = Math.addExact(pageNumber, 1);
            } while (slice.hasNext());
        } catch (RuntimeException exception) {
            log.error("Organization status-cache eviction failed: organizationId={}, authVersion={}",
                    event.organizationId(), event.authVersion(), exception);
        }
    }
}
