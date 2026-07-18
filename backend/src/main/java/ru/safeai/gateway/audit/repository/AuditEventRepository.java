package ru.safeai.gateway.audit.repository;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
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
        JpaSpecificationExecutor<AuditEventEntity> {

    @Override
    @NonNull
    @EntityGraph(attributePaths = "user")
    Page<AuditEventEntity> findAll(
            @NonNull Specification<AuditEventEntity> specification,
            @NonNull Pageable pageable
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    delete from audit_events
                    where id in (
                        select id
                        from audit_events
                        where created_at < :threshold
                        order by created_at, id
                        limit :batchSize
                    )
                    """,
            nativeQuery = true
    )
    int deleteBatchCreatedBefore(
            @Param("threshold") Instant threshold,
            @Param("batchSize") int batchSize
    );
}