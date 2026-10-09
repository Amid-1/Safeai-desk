package ru.safeai.gateway.audit.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.config.AuditOutboxProperties;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public final class AuditOutboxProcessor {

    private final AuditOutboxRepository outboxRepository;
    private final AuditEventRepository eventRepository;
    private final AuditOutboxFailureService failureService;
    private final AuditOutboxMetrics metrics;
    private final AuditOutboxProperties properties;
    private final TransactionTemplate itemTransaction;

    public AuditOutboxProcessor(
            AuditOutboxRepository outboxRepository,
            AuditEventRepository eventRepository,
            AuditOutboxFailureService failureService,
            AuditOutboxMetrics metrics,
            AuditOutboxProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.outboxRepository =
                Objects.requireNonNull(
                        outboxRepository,
                        "outboxRepository не должен быть null"
                );

        this.eventRepository =
                Objects.requireNonNull(
                        eventRepository,
                        "eventRepository не должен быть null"
                );

        this.failureService =
                Objects.requireNonNull(
                        failureService,
                        "failureService не должен быть null"
                );

        this.metrics =
                Objects.requireNonNull(
                        metrics,
                        "metrics не должен быть null"
                );

        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties не должен быть null"
                );

        Objects.requireNonNull(
                transactionManager,
                "transactionManager не должен быть null"
        );

        itemTransaction =
                new TransactionTemplate(
                        transactionManager
                );

        itemTransaction
                .setPropagationBehavior(
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

            ItemResult result =
                    processOne();

            if (result
                    == ItemResult.EMPTY) {

                break;
            }

            if (result
                    == ItemResult.PROCESSED) {

                processed++;
                continue;
            }

            failed++;

            if (result
                    == ItemResult.DEAD_LETTERED) {

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
            Boolean found =
                    itemTransaction.execute(
                            status -> {
                                Optional<AuditOutboxEntity>
                                        optional =
                                        outboxRepository
                                                .findNextForUpdate();

                                if (optional.isEmpty()) {
                                    return false;
                                }

                                AuditOutboxEntity item =
                                        optional.get();

                                selectedId.set(
                                        item.getId()
                                );

                                int insertedRows =
                                        eventRepository
                                                .insertFromOutbox(
                                                        item.getId()
                                                );

                                /*
                                 * 1 = inserted.
                                 * 0 = same event id already exists: delivery is
                                 * idempotently complete.
                                 */
                                if (insertedRows != 0
                                        && insertedRows != 1) {

                                    throw new IllegalStateException(
                                            "Unexpected insertFromOutbox affected rows: "
                                                    + insertedRows
                                    );
                                }

                                outboxRepository.delete(
                                        item
                                );

                                outboxRepository.flush();

                                return true;
                            }
                    );

            return Boolean.TRUE.equals(
                    found
            )
                    ? ItemResult.PROCESSED
                    : ItemResult.EMPTY;
        } catch (RuntimeException failure) {
            UUID outboxId =
                    selectedId.get();

            if (outboxId == null) {
                throw failure;
            }

            AuditOutboxFailureService.FailureResult
                    result =
                    failureService.markFailure(
                            outboxId,
                            failure
                    );

            metrics.recordDeliveryFailure(
                    result.deadLettered()
            );

            if (!result.rowFound()) {
                /*
                 * Uncertain commit: the row may have disappeared because the
                 * insert+delete transaction actually committed.
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
