package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.config.AuditRetentionProperties;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditRetentionServiceTest {

    private static final Duration RETENTION_PERIOD =
            Duration.ofDays(
                    365
            );

    private static final int BATCH_SIZE =
            100;

    @Mock
    private AuditRetentionBatchService batchService;

    @Mock
    private AuditRetentionLockService lockService;

    @Test
    void skipsCleanupWhenAnotherInstanceOwnsLock() {
        AuditRetentionService service =
                service(
                        true,
                        3
                );

        when(
                lockService.tryExecute(
                        anyIntegerSupplier()
                )
        ).thenReturn(
                AuditRetentionLockService
                        .LockExecution
                        .notAcquired()
        );

        service.cleanupExpiredAuditEvents();

        verify(
                batchService,
                never()
        ).deleteBatch(
                any(),
                anyInt()
        );
    }

    @Test
    void stopsWhenBatchIsNotFull() {
        AuditRetentionService service =
                service(
                        true,
                        5
                );

        executeSupplierUnderLock();

        when(
                batchService.deleteBatch(
                        RETENTION_PERIOD,
                        BATCH_SIZE
                )
        ).thenReturn(
                100,
                23
        );

        service.cleanupExpiredAuditEvents();

        verify(
                batchService,
                times(
                        2
                )
        ).deleteBatch(
                RETENTION_PERIOD,
                BATCH_SIZE
        );
    }

    @Test
    void maxBatchesPreventsUnboundedCleanupLoop() {
        AuditRetentionService service =
                service(
                        true,
                        3
                );

        executeSupplierUnderLock();

        when(
                batchService.deleteBatch(
                        RETENTION_PERIOD,
                        BATCH_SIZE
                )
        ).thenReturn(
                BATCH_SIZE
        );

        service.cleanupExpiredAuditEvents();

        verify(
                batchService,
                times(
                        3
                )
        ).deleteBatch(
                RETENTION_PERIOD,
                BATCH_SIZE
        );
    }

    @Test
    void batchFailureStopsCurrentRunImmediately() {
        AuditRetentionService service =
                service(
                        true,
                        10
                );

        executeSupplierUnderLock();

        when(
                batchService.deleteBatch(
                        RETENTION_PERIOD,
                        BATCH_SIZE
                )
        ).thenThrow(
                new IllegalStateException(
                        "database failure"
                )
        );

        assertThatThrownBy(
                service::cleanupExpiredAuditEvents
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "database failure"
                );

        verify(
                batchService
        ).deleteBatch(
                RETENTION_PERIOD,
                BATCH_SIZE
        );
    }

    @Test
    void disabledRetentionDoesNotAcquireLock() {
        AuditRetentionService service =
                service(
                        false,
                        3
                );

        service.cleanupExpiredAuditEvents();

        verify(
                lockService,
                never()
        ).tryExecute(
                anyIntegerSupplier()
        );

        verify(
                batchService,
                never()
        ).deleteBatch(
                any(),
                anyInt()
        );
    }

    private void executeSupplierUnderLock() {
        when(
                lockService.tryExecute(
                        anyIntegerSupplier()
                )
        ).thenAnswer(
                invocation -> {
                    Supplier<Integer> supplier =
                            invocation.getArgument(
                                    0
                            );

                    return AuditRetentionLockService
                            .LockExecution
                            .acquired(
                                    supplier.get()
                            );
                }
        );
    }

    private AuditRetentionService service(
            boolean enabled,
            int maxBatches
    ) {
        return new AuditRetentionService(
                batchService,
                lockService,
                new AuditRetentionProperties(
                        enabled,
                        RETENTION_PERIOD,
                        BATCH_SIZE,
                        maxBatches
                )
        );
    }

    private static Supplier<Integer> anyIntegerSupplier() {
        return ArgumentMatchers.any();
    }
}
