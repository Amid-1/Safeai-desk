package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import ru.safeai.gateway.user.event.UserSecurityStateChangedEvent;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class UserStatusCacheInvalidationIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private UserStatusCacheService userStatusCacheService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void cacheIsInvalidatedOnlyAfterCommit() {
        UUID userId = UUID.randomUUID();
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(
                    new UserSecurityStateChangedEvent(userId)
            );

            verify(userStatusCacheService, never())
                    .evict(userId);
        });

        verify(userStatusCacheService).evict(userId);
    }

    @Test
    void rollbackDoesNotInvalidateCache() {
        UUID userId = UUID.randomUUID();
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        reset(userStatusCacheService);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(
                    new UserSecurityStateChangedEvent(userId)
            );
            status.setRollbackOnly();
        });

        verify(userStatusCacheService, never())
                .evict(userId);
    }
}
