package ru.safeai.gateway.usage.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.usage.config.UsageJdbcClients;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Repository
public class UsageDirtyDateRepository {
    private final JdbcTemplate jdbc;

    public UsageDirtyDateRepository(UsageJdbcClients clients) {
        this.jdbc = Objects.requireNonNull(clients, "clients").rollup();
    }

    /** Reads a bounded batch; acquiring row locks occurs only in the processing transaction. */
    public List<DirtyDate> findPending(LocalDate latestClosedDate, int limit) {
        Objects.requireNonNull(latestClosedDate, "latestClosedDate");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT usage_date, revision
                FROM public.usage_dirty_dates
                WHERE usage_date <= ?
                ORDER BY usage_date
                LIMIT ?
                """, (rs, row) -> new DirtyDate(
                rs.getObject("usage_date", LocalDate.class),
                rs.getLong("revision")
        ), Date.valueOf(latestClosedDate), limit);
    }

    /** Must be invoked inside a write transaction. Serializes rebuild against trigger upserts. */
    public DirtyDate lockDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        List<DirtyDate> rows = jdbc.query("""
                SELECT usage_date, revision
                FROM public.usage_dirty_dates
                WHERE usage_date = ?
                FOR UPDATE
                """, (rs, row) -> new DirtyDate(
                rs.getObject("usage_date", LocalDate.class),
                rs.getLong("revision")
        ), Date.valueOf(date));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Delete is safe only in the SAME transaction after lockDate() and successful rebuild. */
    public boolean acknowledgeIfUnchanged(DirtyDate item) {
        Objects.requireNonNull(item, "item");
        return jdbc.update("""
                DELETE FROM public.usage_dirty_dates
                WHERE usage_date = ? AND revision = ?
                """, Date.valueOf(item.date()), item.revision()) == 1;
    }

    /** Explicit request; callers must enforce authorization and a closed UTC day. */
    public void requestRebuild(LocalDate date) {
        Objects.requireNonNull(date, "date");
        int changed = jdbc.update("""
                INSERT INTO public.usage_dirty_dates (usage_date, revision, updated_at)
                VALUES (?, 1, clock_timestamp())
                ON CONFLICT (usage_date) DO UPDATE
                    SET revision = public.usage_dirty_dates.revision + 1,
                        updated_at = clock_timestamp()
                """, Date.valueOf(date));
        if (changed != 1) {
            throw new IllegalStateException("Unable to enqueue usage dirty date " + date);
        }
    }

    public record DirtyDate(LocalDate date, long revision) {
        public DirtyDate {
            Objects.requireNonNull(date, "date");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }
}
