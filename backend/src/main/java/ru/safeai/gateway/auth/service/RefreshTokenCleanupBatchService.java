package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;

import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupBatchService {

    private static final int MAX_BATCH_SIZE = 10_000;

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Каждый batch фиксируется отдельной физической транзакцией.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteNextBatch(
            Instant threshold,
            int batchSize
    ) {
        Objects.requireNonNull(
                threshold,
                "threshold не должен быть null"
        );

        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize должен находиться в диапазоне 1.."
                            + MAX_BATCH_SIZE
            );
        }

        int deletedRows =
                refreshTokenRepository.deleteExpiredBatch(
                        threshold,
                        batchSize
                );

        if (deletedRows < 0 || deletedRows > batchSize) {
            throw new IllegalStateException(
                    "Repository вернул некорректное количество "
                            + "удалённых строк: "
                            + deletedRows
            );
        }

        return deletedRows;
    }
}