package ru.safeai.gateway.audit.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.config.AuditOutboxProperties;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AuditOutboxProcessor {

    private final AuditOutboxRepository outboxRepository;
    private final AuditEventRepository eventRepository;
    private final AuditOutboxFailureService failureService;
    private final AuditOutboxMetrics metrics;
    private final AuditOutboxProperties properties;
    private final Clock clock;
    private final TransactionTemplate itemTransaction;

    public AuditOutboxProcessor(
            AuditOutboxRepository outboxRepository,
            AuditEventRepository eventRepository,
            AuditOutboxFailureService failureService,
            AuditOutboxMetrics metrics,
            AuditOutboxProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.outboxRepository = outboxRepository;
        this.eventRepository = eventRepository;
        this.failureService = failureService;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;

        itemTransaction =
                new TransactionTemplate(
                        transactionManager
                );

        itemTransaction.setPropagationBehavior(
                TransactionDefinition
                        .PROPAGATION_REQUIRES_NEW
        );
    }

    public BatchResult processBatch() {
        int processed = 0;
        int failed = 0;
        int deadLettered = 0;

        for (int index = 0;
             index < properties.effectiveBatchSize();
             index++) {

            ItemResult result = processOne();

            if (result == ItemResult.EMPTY) {
                break;
            }

            if (result == ItemResult.PROCESSED) {
                processed++;
                continue;
            }

            failed++;

            if (result == ItemResult.DEAD_LETTERED) {
                deadLettered++;
            }
        }

        return new BatchResult(
                processed,
                failed,
                deadLettered
        );
    }

    private ItemResult processOne() {
        AtomicReference<UUID> selectedId =
                new AtomicReference<>();

        try {
            Boolean found = itemTransaction.execute(
                    status -> {
                        Optional<AuditOutboxEntity> optional =
                                outboxRepository
                                        .findNextForUpdate(
                                                clock.instant()
                                        );

                        if (optional.isEmpty()) {
                            return false;
                        }

                        AuditOutboxEntity item =
                                optional.get();

                        selectedId.set(item.getId());

                        int insertedRows =
                                eventRepository
                                        .insertFromOutbox(
                                                item.getId()
                                        );

                        /*
                         * 1 — event inserted.
                         * 0 — event already exists, therefore delivery is
                         * idempotently complete.
                         */
                        if (insertedRows != 0
                                && insertedRows != 1) {
                            throw new IllegalStateException(
                                    "Unexpected insertFromOutbox "
                                            + "affected rows: "
                                            + insertedRows
                            );
                        }

                        outboxRepository.delete(item);
                        outboxRepository.flush();

                        return true;
                    }
            );

            return Boolean.TRUE.equals(found)
                    ? ItemResult.PROCESSED
                    : ItemResult.EMPTY;
        } catch (RuntimeException failure) {
            UUID outboxId = selectedId.get();

            if (outboxId == null) {
                throw failure;
            }

            AuditOutboxFailureService.FailureResult result =
                    failureService.markFailure(
                            outboxId,
                            failure
                    );

            metrics.recordDeliveryFailure(
                    result.deadLettered()
            );

            if (!result.rowFound()) {
                /*
                 * The row may have disappeared after an uncertain commit.
                 * Because audit_events.id is idempotent, this is treated as
                 * completed delivery.
                 */
                return ItemResult.PROCESSED;
            }

            return result.deadLettered()
                    ? ItemResult.DEAD_LETTERED
                    : ItemResult.FAILED;
        }
    }

    private enum ItemResult {
        EMPTY,
        PROCESSED,
        FAILED,
        DEAD_LETTERED
    }

    public record BatchResult(
            int processed,
            int failed,
            int deadLettered
    ) {
    }
}
