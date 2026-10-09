package ru.safeai.gateway.audit.repository;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.time.Instant;
import java.util.UUID;

public interface AuditEventRepository
        extends JpaRepository<AuditEventEntity, UUID>,
        JpaSpecificationExecutor<AuditEventEntity>,
        AuditEventCursorRepository {

    @Override
    @NonNull
    Page<AuditEventEntity> findAll(
            @NonNull Specification<AuditEventEntity> specification,
            @NonNull Pageable pageable
    );

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    insert into public.audit_events (
                        id,
                        actor_user_id,
                        actor_organization_id,
                        actor_email,
                        actor_display_name,
                        organization_id,
                        target_organization_name,
                        event_type,
                        details,
                        created_at
                    )
                    select outbox.id,
                           outbox.actor_user_id,
                           outbox.actor_organization_id,
                           outbox.actor_email,
                           outbox.actor_display_name,
                           outbox.organization_id,
                           outbox.target_organization_name,
                           outbox.event_type,
                           outbox.details,
                           outbox.occurred_at
                    from public.audit_outbox as outbox
                    where outbox.id = :outboxId
                    on conflict (id) do nothing
                    """,
            nativeQuery = true
    )
    int insertFromOutbox(
            @Param("outboxId") UUID outboxId
    );

    /**
     * Retention cutoff is evaluated by PostgreSQL so a fast/slow JVM clock
     * cannot delete immutable evidence early.
     * Associated live-user links are removed by ON DELETE CASCADE.
     */
    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    delete from public.audit_events as audit_event
                    where audit_event.id in (
                        select candidate.id
                        from public.audit_events as candidate
                        where candidate.created_at
                              < clock_timestamp()
                                - make_interval(
                                    secs => cast(:retentionSeconds as double precision)
                                  )
                        order by candidate.created_at,
                                 candidate.id
                        limit :batchSize
                    )
                    """,
            nativeQuery = true
    )
    int deleteRetentionBatch(
            @Param("retentionSeconds") long retentionSeconds,
            @Param("batchSize") int batchSize
    );
}
