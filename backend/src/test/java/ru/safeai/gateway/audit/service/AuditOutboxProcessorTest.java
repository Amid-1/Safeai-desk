package ru.safeai.gateway.audit.service;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import ru.safeai.gateway.audit.config.AuditOutboxProperties;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditOutboxProcessorTest {

    private static final Instant NOW =
            Instant.parse(
                    "2026-07-30T08:00:00Z"
            );

    @Mock
    private AuditOutboxRepository outboxRepository;

    @Mock
    private AuditEventRepository eventRepository;

    @Mock
    private AuditOutboxFailureService failureService;

    @Mock
    private AuditOutboxMetrics metrics;

    @Test
    void copiesEventBeforeDeletingOutboxRow() {
        AuditOutboxEntity item =
                item();

        whenNextRows(
                item
        );

        when(
                eventRepository.insertFromOutbox(
                        item.getId()
                )
        ).thenReturn(
                1
        );

        AuditOutboxProcessor.BatchResult result =
                processor(
                        new TestTransactionManager()
                ).processBatch();

        assertThat(
                result.processed()
        ).isEqualTo(
                1
        );

        assertThat(
                result.failed()
        ).isZero();

        assertThat(
                result.deadLettered()
        ).isZero();

        InOrder order =
                inOrder(
                        eventRepository,
                        outboxRepository
                );

        order.verify(
                eventRepository
        ).insertFromOutbox(
                item.getId()
        );

        order.verify(
                outboxRepository
        ).delete(
                item
        );

        order.verify(
                outboxRepository
        ).flush();
    }

    @Test
    void alreadyDeliveredEventIsTreatedAsIdempotentSuccess() {
        AuditOutboxEntity item =
                item();

        whenNextRows(
                item
        );

        when(
                eventRepository.insertFromOutbox(
                        item.getId()
                )
        ).thenReturn(
                0
        );

        AuditOutboxProcessor.BatchResult result =
                processor(
                        new TestTransactionManager()
                ).processBatch();

        assertThat(
                result.processed()
        ).isEqualTo(
                1
        );

        assertThat(
                result.failed()
        ).isZero();

        verify(
                outboxRepository
        ).delete(
                item
        );

        verify(
                outboxRepository
        ).flush();
    }

    @Test
    void poisonRowGetsBackoffAndDoesNotBlockNextRow() {
        AuditOutboxEntity poison =
                item();

        AuditOutboxEntity valid =
                item();

        whenNextRows(
                poison,
                valid
        );

        when(
                eventRepository.insertFromOutbox(
                        poison.getId()
                )
        ).thenThrow(
                new DataIntegrityViolationException(
                        "invalid event"
                )
        );

        when(
                eventRepository.insertFromOutbox(
                        valid.getId()
                )
        ).thenReturn(
                1
        );

        when(
                failureService.markFailure(
                        eq(poison.getId()),
                        any(RuntimeException.class)
                )
        ).thenReturn(
                new AuditOutboxFailureService
                        .FailureResult(
                        poison.getId(),
                        true,
                        false,
                        1
                )
        );

        AuditOutboxProcessor.BatchResult result =
                processor(
                        new TestTransactionManager()
                ).processBatch();

        assertThat(
                result.processed()
        ).isEqualTo(
                1
        );

        assertThat(
                result.failed()
        ).isEqualTo(
                1
        );

        assertThat(
                result.deadLettered()
        ).isZero();

        verify(
                eventRepository
        ).insertFromOutbox(
                valid.getId()
        );

        verify(
                outboxRepository
        ).delete(
                valid
        );

        verify(
                metrics
        ).recordDeliveryFailure(
                false
        );
    }

    @Test
    void maxAttemptFailureIsReportedAsDeadLetter() {
        AuditOutboxEntity poison =
                item();

        whenNextRows(
                poison
        );

        when(
                eventRepository.insertFromOutbox(
                        poison.getId()
                )
        ).thenThrow(
                new DataIntegrityViolationException(
                        "invalid event"
                )
        );

        when(
                failureService.markFailure(
                        eq(poison.getId()),
                        any(RuntimeException.class)
                )
        ).thenReturn(
                new AuditOutboxFailureService
                        .FailureResult(
                        poison.getId(),
                        true,
                        true,
                        10
                )
        );

        AuditOutboxProcessor.BatchResult result =
                processor(
                        new TestTransactionManager()
                ).processBatch();

        assertThat(
                result.processed()
        ).isZero();

        assertThat(
                result.failed()
        ).isEqualTo(
                1
        );

        assertThat(
                result.deadLettered()
        ).isEqualTo(
                1
        );

        verify(
                metrics
        ).recordDeliveryFailure(
                true
        );
    }

    @Test
    void commitFailureIsCaughtAndFailurePolicyIsApplied() {
        AuditOutboxEntity item =
                item();

        whenNextRows(
                item
        );

        when(
                eventRepository.insertFromOutbox(
                        item.getId()
                )
        ).thenReturn(
                1
        );

        when(
                failureService.markFailure(
                        eq(item.getId()),
                        any(RuntimeException.class)
                )
        ).thenReturn(
                new AuditOutboxFailureService
                        .FailureResult(
                        item.getId(),
                        true,
                        false,
                        1
                )
        );

        TestTransactionManager transactionManager =
                new TestTransactionManager();

        transactionManager.failNextCommit();

        AuditOutboxProcessor.BatchResult result =
                processor(
                        transactionManager
                ).processBatch();

        assertThat(
                result.processed()
        ).isZero();

        assertThat(
                result.failed()
        ).isEqualTo(
                1
        );

        verify(
                failureService
        ).markFailure(
                eq(item.getId()),
                any(RuntimeException.class)
        );

        verify(
                metrics
        ).recordDeliveryFailure(
                false
        );
    }

    @Test
    void uncertainCommitWithMissingOutboxRowIsTreatedAsDelivered() {
        AuditOutboxEntity item =
                item();

        whenNextRows(
                item
        );

        when(
                eventRepository.insertFromOutbox(
                        item.getId()
                )
        ).thenReturn(
                1
        );

        when(
                failureService.markFailure(
                        eq(item.getId()),
                        any(RuntimeException.class)
                )
        ).thenReturn(
                new AuditOutboxFailureService
                        .FailureResult(
                        item.getId(),
                        false,
                        false,
                        0
                )
        );

        TestTransactionManager transactionManager =
                new TestTransactionManager();

        transactionManager.failNextCommit();

        AuditOutboxProcessor.BatchResult result =
                processor(
                        transactionManager
                ).processBatch();

        assertThat(
                result.processed()
        ).isEqualTo(
                1
        );

        assertThat(
                result.failed()
        ).isZero();

        /*
         * Processor still observes the failed commit attempt,
         * therefore delivery failure telemetry is recorded even
         * though the missing outbox row proves that delivery most
         * likely committed successfully.
         */
        verify(
                metrics
        ).recordDeliveryFailure(
                false
        );
    }

    @Test
    void successfulDeliveryDoesNotRecordFailureMetric() {
        AuditOutboxEntity item =
                item();

        whenNextRows(
                item
        );

        when(
                eventRepository.insertFromOutbox(
                        item.getId()
                )
        ).thenReturn(
                1
        );

        AuditOutboxProcessor.BatchResult result =
                processor(
                        new TestTransactionManager()
                ).processBatch();

        assertThat(
                result.processed()
        ).isEqualTo(
                1
        );

        verify(
                metrics,
                never()
        ).recordDeliveryFailure(
                any(Boolean.class)
        );
    }

    private void whenNextRows(
            AuditOutboxEntity... rows
    ) {
        OngoingStubbing<
                Optional<AuditOutboxEntity>
                > stubbing =
                when(
                        outboxRepository
                                .findNextForUpdate(
                                        NOW
                                )
                );

        for (AuditOutboxEntity row : rows) {
            stubbing =
                    stubbing.thenReturn(
                            Optional.of(row)
                    );
        }

        stubbing.thenReturn(
                Optional.empty()
        );
    }

    private AuditOutboxProcessor processor(
            TestTransactionManager transactionManager
    ) {
        return new AuditOutboxProcessor(
                outboxRepository,
                eventRepository,
                failureService,
                metrics,
                new AuditOutboxProperties(
                        10,
                        10,
                        Duration.ofSeconds(2),
                        Duration.ofHours(1),
                        1_000
                ),
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                ),
                transactionManager
        );
    }

    private AuditOutboxEntity item() {
        AuditOutboxEntity item =
                new AuditOutboxEntity();

        item.setId(
                UUID.randomUUID()
        );

        item.setOccurredAt(
                NOW
        );

        item.setCreatedAt(
                NOW
        );

        item.setNextAttemptAt(
                NOW
        );

        item.setAttemptCount(
                0
        );

        return item;
    }

    @NullMarked
    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        private final AtomicBoolean failCommit =
                new AtomicBoolean();

        void failNextCommit() {
            failCommit.set(
                    true
            );
        }

        @Override
        protected Object doGetTransaction()
                throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition
        ) throws TransactionException {
            // No resource is required for this transaction-boundary test.
        }

        @Override
        protected void doCommit(
                DefaultTransactionStatus status
        ) throws TransactionException {
            if (failCommit.compareAndSet(
                    true,
                    false
            )) {
                throw new TransactionException(
                        "simulated commit failure"
                ) {
                };
            }
        }

        @Override
        protected void doRollback(
                DefaultTransactionStatus status
        ) throws TransactionException {
            // No resource is required for this transaction-boundary test.
        }
    }
}