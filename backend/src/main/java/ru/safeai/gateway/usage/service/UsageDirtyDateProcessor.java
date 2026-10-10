package ru.safeai.gateway.usage.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.usage.repository.UsageDirtyDateRepository;

import java.time.LocalDate;
import java.util.Objects;

/** One dirty date per new READ COMMITTED transaction, including the row lock. */
@Service
public class UsageDirtyDateProcessor {
    private final UsageDirtyDateRepository dirtyDates;
    private final UsageRollupDayProcessor dayProcessor;

    public UsageDirtyDateProcessor(
            UsageDirtyDateRepository dirtyDates,
            UsageRollupDayProcessor dayProcessor
    ) {
        this.dirtyDates = Objects.requireNonNull(dirtyDates, "dirtyDates");
        this.dayProcessor = Objects.requireNonNull(dayProcessor, "dayProcessor");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean rebuildIfPending(LocalDate date) {
        Objects.requireNonNull(date, "date");
        UsageDirtyDateRepository.DirtyDate locked = dirtyDates.lockDate(date);
        if (locked == null) {
            return false;
        }

        // Joins THIS transaction: DELETE/INSERT rollups + acknowledgement commit atomically.
        dayProcessor.rebuildDay(date, false);
        if (!dirtyDates.acknowledgeIfUnchanged(locked)) {
            throw new IllegalStateException("Dirty date revision changed unexpectedly: " + date);
        }
        return true;
    }
}
