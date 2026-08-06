package ru.safeai.gateway.organization.service;

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
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.util.Objects;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrganizationStatusCacheInvalidationListener {

    private static final int PAGE_SIZE = 1_000;

    private final UserRepository
            userRepository;

    private final UserStatusCacheService
            userStatusCacheService;

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
        Objects.requireNonNull(
                event,
                "event не должен быть null"
        );

        int pageNumber = 0;

        try {
            Slice<UUID> slice;

            do {
                slice = userRepository
                        .findIdsByOrganizationId(
                                event.organizationId(),
                                PageRequest.of(
                                        pageNumber,
                                        PAGE_SIZE,
                                        Sort.by(
                                                Sort.Order.asc(
                                                        "id"
                                                )
                                        )
                                )
                        );

                if (!slice.isEmpty()) {
                    userStatusCacheService.evictAll(
                            slice.getContent()
                    );
                }

                pageNumber =
                        Math.addExact(
                                pageNumber,
                                1
                        );
            } while (slice.hasNext());
        } catch (
                RuntimeException exception
        ) {
            /*
             * Это best-effort оптимизация. Source of truth остаётся
             * PostgreSQL, поэтому ошибка cache eviction не должна
             * откатывать уже зафиксированную security mutation.
             */
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
