package ru.safeai.gateway.audit.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuditOutboxRepository
        extends JpaRepository<AuditOutboxEntity, UUID> {

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    @Query(
            value = """
                    select outbox.*
                    from public.audit_outbox as outbox
                    where outbox.dead_lettered_at is null
                      and outbox.next_attempt_at <= :now
                    order by outbox.created_at,
                             outbox.id
                    limit 1
                    for update of outbox skip locked
                    """,
            nativeQuery = true
    )
    Optional<AuditOutboxEntity> findNextForUpdate(
            @Param("now") Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select outbox
            from AuditOutboxEntity outbox
            where outbox.id = :id
            """)
    Optional<AuditOutboxEntity> findByIdForUpdate(
            @Param("id") UUID id
    );
}
