package ru.safeai.gateway.knowledge.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeHealthResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeReindexResponse;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseMembershipEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseMembershipRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeOperationsService {

    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseMembershipRepository memberships;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeEmbeddingProvider embeddingProvider;
    private final JdbcTemplate jdbc;
    private final AuditEventService audit;
    private final Clock clock;

    public KnowledgeOperationsService(
            KnowledgeBaseRepository bases,
            KnowledgeBaseMembershipRepository memberships,
            KnowledgeDocumentRepository documents,
            KnowledgeEmbeddingProvider embeddingProvider,
            JdbcTemplate jdbc,
            AuditEventService audit,
            Clock clock
    ) {
        this.bases = bases;
        this.memberships = memberships;
        this.documents = documents;
        this.embeddingProvider = embeddingProvider;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public KnowledgeHealthResponse health(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user
    ) {
        authorizeRead(knowledgeBaseId, user);
        Counts counts = jdbc.queryForObject("""
                select
                    count(*) as total_documents,
                    count(*) filter (where document.enabled) as enabled_documents,
                    count(*) filter (
                        where document.enabled and exists (
                            select 1
                            from knowledge_document_chunks chunk
                            join knowledge_ingestion_jobs active_job
                              on active_job.document_version_id = chunk.document_version_id
                             and active_job.index_generation = chunk.index_generation
                            where chunk.document_version_id = document.current_version_id
                        )
                    ) as searchable_documents,
                    count(*) filter (where job.status = 'PENDING') as pending_documents,
                    count(*) filter (
                        where job.status in ('VALIDATING','EXTRACTING','CHUNKING')
                    ) as processing_documents,
                    count(*) filter (where job.status = 'FAILED') as failed_documents,
                    count(*) filter (
                        where document.enabled
                          and job.embedding_model is distinct from ?
                          and exists (
                              select 1
                              from knowledge_document_chunks active_chunk
                              where active_chunk.document_version_id = job.document_version_id
                                and active_chunk.index_generation = job.index_generation
                          )
                    ) as stale_embedding_documents,
                    coalesce((
                        select count(*)
                        from knowledge_document_chunks chunk
                        join knowledge_ingestion_jobs active_job
                          on active_job.document_version_id = chunk.document_version_id
                         and active_job.index_generation = chunk.index_generation
                        where chunk.organization_id = ?
                          and chunk.knowledge_base_id = ?
                    ), 0) as active_chunks
                from knowledge_documents document
                left join knowledge_ingestion_jobs job
                  on job.document_version_id = document.current_version_id
                 and job.organization_id = document.organization_id
                where document.organization_id = ?
                  and document.knowledge_base_id = ?
                """,
                this::mapCounts,
                embeddingProvider.model(),
                user.getOrganizationId(),
                knowledgeBaseId,
                user.getOrganizationId(),
                knowledgeBaseId
        );
        String state = counts.totalDocuments() == 0
                ? "EMPTY"
                : counts.failedDocuments() > 0
                        || counts.staleEmbeddingDocuments() > 0
                        ? "DEGRADED"
                        : counts.searchableDocuments() == counts.enabledDocuments()
                                ? "HEALTHY"
                                : "INDEXING";
        return new KnowledgeHealthResponse(
                knowledgeBaseId,
                state,
                embeddingProvider.model(),
                counts.totalDocuments(),
                counts.enabledDocuments(),
                counts.searchableDocuments(),
                counts.pendingDocuments(),
                counts.processingDocuments(),
                counts.failedDocuments(),
                counts.staleEmbeddingDocuments(),
                counts.activeChunks(),
                clock.instant()
        );
    }

    @Transactional
    public KnowledgeReindexResponse reindex(
            UUID knowledgeBaseId,
            UUID documentId,
            SafeAiUserPrincipal user
    ) {
        authorizeWrite(knowledgeBaseId, user);
        KnowledgeDocumentEntity document = documents
                .findForUpdate(
                        documentId,
                        knowledgeBaseId,
                        user.getOrganizationId()
                )
                .orElseThrow(this::notFound);
        if (document.getCurrentVersionId() == null) {
            throw new ConflictException("У документа нет текущей версии.");
        }

        JobRow job = jdbc.queryForObject("""
                select id, status
                from knowledge_ingestion_jobs
                where document_version_id = ?
                  and document_id = ?
                  and knowledge_base_id = ?
                  and organization_id = ?
                for update
                """,
                this::mapJob,
                document.getCurrentVersionId(),
                documentId,
                knowledgeBaseId,
                user.getOrganizationId()
        );
        if (job == null) {
            throw notFound();
        }
        if (java.util.Set.of(
                "VALIDATING", "EXTRACTING", "CHUNKING"
        ).contains(job.status())) {
            throw new ConflictException("Reindex уже выполняется.");
        }

        Instant now = clock.instant();
        int updated = jdbc.update("""
                update knowledge_ingestion_jobs
                set status = 'PENDING',
                    attempt = 0,
                    processing_token = null,
                    claimed_at = null,
                    lease_until = null,
                    next_attempt_at = ?,
                    error_code = null,
                    error_message = null,
                    started_at = null,
                    finished_at = null,
                    updated_at = ?,
                    version = version + 1
                where id = ?
                  and status = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                job.id(),
                job.status()
        );
        if (updated != 1) {
            throw new ConflictException("Состояние ingestion job изменилось.");
        }

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_REINDEX_REQUESTED,
                Map.of(
                        "knowledgeBaseId", knowledgeBaseId.toString(),
                        "documentId", documentId.toString(),
                        "documentVersionId", document.getCurrentVersionId().toString(),
                        "ingestionJobId", job.id().toString()
                )
        );
        return new KnowledgeReindexResponse(
                knowledgeBaseId,
                documentId,
                document.getCurrentVersionId(),
                job.id(),
                "PENDING",
                now
        );
    }

    private void authorizeRead(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user
    ) {
        KnowledgeBaseEntity base = base(knowledgeBaseId, user);
        if (isAdmin(user)) {
            return;
        }
        if (!base.isEnabled()) {
            throw notFound();
        }
        if (base.getVisibility() == KnowledgeBaseVisibility.MEMBERS
                && membership(knowledgeBaseId, user) == null) {
            throw notFound();
        }
    }

    private void authorizeWrite(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user
    ) {
        KnowledgeBaseEntity base = base(knowledgeBaseId, user);
        if (isAdmin(user)) {
            return;
        }
        if (!base.isEnabled()) {
            throw notFound();
        }
        KnowledgeBaseMembershipEntity membership =
                membership(knowledgeBaseId, user);
        if (membership == null
                || membership.getAccessLevel()
                == KnowledgeBaseAccessLevel.VIEWER) {
            throw new ForbiddenOperationException(
                    "Для reindex требуется доступ EDITOR или OWNER."
            );
        }
    }

    private KnowledgeBaseEntity base(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user
    ) {
        return bases.findByIdAndOrganizationId(
                knowledgeBaseId,
                user.getOrganizationId()
        ).orElseThrow(this::notFound);
    }

    private KnowledgeBaseMembershipEntity membership(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal user
    ) {
        return memberships
                .findByKnowledgeBaseIdAndOrganizationIdAndUserId(
                        knowledgeBaseId,
                        user.getOrganizationId(),
                        user.getId()
                )
                .orElse(null);
    }

    private static boolean isAdmin(SafeAiUserPrincipal user) {
        return user.getAuthorities().stream().anyMatch(authority ->
                "ROLE_ADMIN".equals(authority.getAuthority())
        );
    }

    private Counts mapCounts(ResultSet rs, int rowNumber) throws SQLException {
        return new Counts(
                rs.getLong("total_documents"),
                rs.getLong("enabled_documents"),
                rs.getLong("searchable_documents"),
                rs.getLong("pending_documents"),
                rs.getLong("processing_documents"),
                rs.getLong("failed_documents"),
                rs.getLong("stale_embedding_documents"),
                rs.getLong("active_chunks")
        );
    }

    private JobRow mapJob(ResultSet rs, int rowNumber) throws SQLException {
        return new JobRow(
                rs.getObject("id", UUID.class),
                rs.getString("status")
        );
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Ресурс Knowledge не найден.");
    }

    private record JobRow(UUID id, String status) {
    }

    private record Counts(
            long totalDocuments,
            long enabledDocuments,
            long searchableDocuments,
            long pendingDocuments,
            long processingDocuments,
            long failedDocuments,
            long staleEmbeddingDocuments,
            long activeChunks
    ) {
    }
}
