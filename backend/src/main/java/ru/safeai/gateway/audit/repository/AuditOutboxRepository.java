package ru.safeai.gateway.audit.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;

import java.util.List;
import java.util.UUID;

public interface AuditOutboxRepository
        extends JpaRepository<AuditOutboxEntity, UUID> {

    /*
     * Метод должен вызываться внутри транзакции.
     * FOR UPDATE SKIP LOCKED позволяет нескольким worker-инстансам
     * разбирать outbox без двойной обработки одной строки.
     */
    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    @Query(
            value = """
                    select outbox.*
                    from public.audit_outbox as outbox
                    order by outbox.created_at, outbox.id
                    limit :batchSize
                    for update of outbox skip locked
                    """,
            nativeQuery = true
    )
    List<AuditOutboxEntity> findBatchForUpdate(
            @Param("batchSize") int batchSize
    );
}