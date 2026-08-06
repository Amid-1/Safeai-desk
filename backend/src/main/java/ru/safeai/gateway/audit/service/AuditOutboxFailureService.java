package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.config.AuditOutboxProperties;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditOutboxFailureService {

    private final AuditOutboxRepository repository;
    private final AuditOutboxProperties properties;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureResult markFailure(
            UUID outboxId,
            RuntimeException failure
    ) {
        AuditOutboxEntity entity = repository
                .findByIdForUpdate(outboxId)
                .orElse(null);

        /*
         * Uncertain commit: обработка могла завершиться в PostgreSQL,
         * хотя клиент получил connection error.
         */
        if (entity == null) {
            return FailureResult.missing(outboxId);
        }

        int attemptCount = entity.getAttemptCount() + 1;
        Instant now = clock.instant();

        boolean deadLettered =
                attemptCount
                        >= properties.effectiveMaxAttempts();

        entity.setAttemptCount(attemptCount);
        entity.setLastError(summarizeFailure(failure));

        if (deadLettered) {
            entity.setDeadLetteredAt(now);
            entity.setNextAttemptAt(null);
        } else {
            entity.setNextAttemptAt(
                    now.plus(backoff(attemptCount))
            );
        }

        repository.save(entity);

        return new FailureResult(
                outboxId,
                true,
                deadLettered,
                attemptCount
        );
    }

    private Duration backoff(int attemptCount) {
        Duration initial =
                properties.effectiveInitialBackoff();
        Duration maximum =
                properties.effectiveMaxBackoff();

        int exponent = Math.clamp(attemptCount - 1, 0,
                30
        );

        long multiplier = 1L << exponent;

        try {
            Duration calculated =
                    initial.multipliedBy(multiplier);

            return calculated.compareTo(maximum) > 0
                    ? maximum
                    : calculated;
        } catch (ArithmeticException exception) {
            return maximum;
        }
    }

    private String summarizeFailure(
            RuntimeException failure
    ) {
        Throwable root = failure;

        while (root.getCause() != null
                && root.getCause() != root) {
            root = root.getCause();
        }

        String summary;

        if (root instanceof SQLException sqlException) {
            summary = root.getClass().getName()
                    + "[sqlState="
                    + sqlException.getSQLState()
                    + ",errorCode="
                    + sqlException.getErrorCode()
                    + "]";
        } else {
            /*
             * Exception message не сохраняется:
             * в ней могут быть SQL values или provider payload.
             */
            summary = root.getClass().getName();
        }

        int limit = properties.effectiveMaxErrorLength();

        return summary.length() <= limit
                ? summary
                : summary.substring(0, limit);
    }

    public record FailureResult(
            UUID outboxId,
            boolean rowFound,
            boolean deadLettered,
            int attemptCount
    ) {
        static FailureResult missing(UUID id) {
            return new FailureResult(
                    id,
                    false,
                    false,
                    0
            );
        }
    }
}
