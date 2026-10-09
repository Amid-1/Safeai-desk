package ru.safeai.gateway.audit.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.config.AuditOutboxProperties;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuditOutboxFailureService {

    private final AuditOutboxRepository repository;
    private final AuditOutboxProperties properties;
    private final AuditDatabaseClock databaseClock;

    public AuditOutboxFailureService(
            AuditOutboxRepository repository,
            AuditOutboxProperties properties,
            AuditDatabaseClock databaseClock
    ) {
        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository не должен быть null"
                );

        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties не должен быть null"
                );

        this.databaseClock =
                Objects.requireNonNull(
                        databaseClock,
                        "databaseClock не должен быть null"
                );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public FailureResult markFailure(
            UUID outboxId,
            RuntimeException failure
    ) {
        Objects.requireNonNull(
                outboxId,
                "outboxId не должен быть null"
        );

        Objects.requireNonNull(
                failure,
                "failure не должен быть null"
        );

        AuditOutboxEntity entity =
                repository
                        .findByIdForUpdate(
                                outboxId
                        )
                        .orElse(
                                null
                        );

        /*
         * The delivery transaction may have committed despite a client-side
         * connection error. Missing outbox therefore remains a valid uncertain
         * commit completion case.
         */
        if (entity == null) {
            return FailureResult.missing(
                    outboxId
            );
        }

        if (entity.getDeadLetteredAt()
                != null) {
            return new FailureResult(
                    outboxId,
                    true,
                    true,
                    entity.getAttemptCount()
            );
        }

        int attemptCount =
                Math.addExact(
                        entity.getAttemptCount(),
                        1
                );

        Instant now =
                databaseClock.now();

        boolean deadLettered =
                attemptCount
                        >= properties
                        .effectiveMaxAttempts();

        entity.setAttemptCount(
                attemptCount
        );

        entity.setLastError(
                summarizeFailure(
                        failure
                )
        );

        if (deadLettered) {
            entity.setDeadLetteredAt(
                    now
            );

            entity.setNextAttemptAt(
                    null
            );
        } else {
            entity.setNextAttemptAt(
                    now.plus(
                            backoff(
                                    attemptCount
                            )
                    )
            );
        }

        repository.saveAndFlush(
                entity
        );

        return new FailureResult(
                outboxId,
                true,
                deadLettered,
                attemptCount
        );
    }

    private Duration backoff(
            int attemptCount
    ) {
        Duration initial =
                properties
                        .effectiveInitialBackoff();

        Duration maximum =
                properties
                        .effectiveMaxBackoff();

        int exponent =
                Math.clamp(
                        attemptCount - 1,
                        0,
                        30
                );

        long multiplier =
                1L << exponent;

        try {
            Duration calculated =
                    initial.multipliedBy(
                            multiplier
                    );

            return calculated.compareTo(
                    maximum
            ) > 0
                    ? maximum
                    : calculated;
        } catch (ArithmeticException exception) {
            return maximum;
        }
    }

    private String summarizeFailure(
            RuntimeException failure
    ) {
        String summary =
                safeFailureDescriptor(
                        rootCause(
                                failure
                        )
                );

        int limit =
                properties
                        .effectiveMaxErrorLength();

        return summary.length()
                <= limit
                ? summary
                : summary.substring(
                        0,
                        limit
                );
    }

    private Throwable rootCause(
            Throwable failure
    ) {
        Throwable root =
                Objects.requireNonNull(
                        failure,
                        "failure не должен быть null"
                );

        while (root.getCause() != null
                && root.getCause() != root) {
            root =
                    root.getCause();
        }

        return root;
    }

    private String safeFailureDescriptor(
            Throwable root
    ) {
        if (root
                instanceof SQLException sqlException) {
            /*
             * Never persist SQLException#getMessage(): SQL values or payload
             * fragments may be embedded there.
             */
            return root.getClass()
                    .getName()
                    + "[sqlState="
                    + sqlException.getSQLState()
                    + ",errorCode="
                    + sqlException.getErrorCode()
                    + "]";
        }

        /*
         * Exception messages are intentionally excluded for the same reason:
         * provider payload or business values may be present there.
         */
        return root.getClass()
                .getName();
    }

    public record FailureResult(
            UUID outboxId,
            boolean rowFound,
            boolean deadLettered,
            int attemptCount
    ) {
        static FailureResult missing(
                UUID id
        ) {
            return new FailureResult(
                    id,
                    false,
                    false,
                    0
            );
        }
    }
}
