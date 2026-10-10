package ru.safeai.gateway.usage.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.usage.repository.UsageDirtyDateRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/** Only a trusted admin entry point should call this service. */
@Service
public class UsageForcedRebuildService {
    private final UsageDirtyDateRepository dirtyDates;
    private final Clock clock;

    public UsageForcedRebuildService(UsageDirtyDateRepository dirtyDates, Clock clock) {
        this.dirtyDates = Objects.requireNonNull(dirtyDates, "dirtyDates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public void request(LocalDate usageDate) {
        Objects.requireNonNull(usageDate, "usageDate");
        LocalDate utcToday = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!usageDate.isBefore(utcToday)) {
            throw new IllegalArgumentException("Only closed UTC dates can be rebuilt");
        }
        dirtyDates.requestRebuild(usageDate);
    }
}
