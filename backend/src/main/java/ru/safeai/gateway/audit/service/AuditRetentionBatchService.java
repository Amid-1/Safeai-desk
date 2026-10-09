package ru.safeai.gateway.audit.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.repository.AuditEventRepository;

import java.time.Duration;
import java.util.Objects;

@Service
public class AuditRetentionBatchService {

    private final AuditEventRepository repository;

    public AuditRetentionBatchService(
            AuditEventRepository repository
    ) {
        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository не должен быть null"
                );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public int deleteBatch(
            Duration retentionPeriod,
            int batchSize
    ) {
        Objects.requireNonNull(
                retentionPeriod,
                "retentionPeriod не должен быть null"
        );

        if (retentionPeriod.isZero()
                || retentionPeriod.isNegative()) {
            throw new IllegalArgumentException(
                    "retentionPeriod должен быть положительным"
            );
        }

        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "batchSize должен быть положительным"
            );
        }

        long retentionSeconds =
                retentionPeriod.getSeconds();

        if (retentionSeconds <= 0) {
            throw new IllegalArgumentException(
                    "retentionPeriod должен быть не меньше одной секунды"
            );
        }

        return repository
                .deleteRetentionBatch(
                        retentionSeconds,
                        batchSize
                );
    }
}
