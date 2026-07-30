package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.repository.AuditEventRepository;

import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuditRetentionBatchService {

    private final AuditEventRepository repository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public int deleteBatch(
            Instant threshold,
            int batchSize
    ) {
        Objects.requireNonNull(
                threshold,
                "threshold не должен быть null"
        );

        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "batchSize должен быть положительным"
            );
        }

        return repository.deleteBatchCreatedBefore(
                threshold,
                batchSize
        );
    }
}
