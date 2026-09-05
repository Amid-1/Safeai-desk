package ru.safeai.gateway.knowledge.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.KnowledgeHealthResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeReindexResponse;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeHealthState;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeOperationsService {

    private static final Set<String> PROCESSING_STATUSES =
            Set.of(
                    "VALIDATING",
                    "EXTRACTING",
                    "CHUNKING"
            );

    private final KnowledgeAccessService accessService;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeEmbeddingProvider embeddingProvider;
    private final JdbcTemplate jdbc;
    private final AuditEventService audit;
    private final Clock clock;

    public KnowledgeOperationsService(
            KnowledgeAccessService accessService,
            KnowledgeDocumentRepository documents,
            KnowledgeEmbeddingProvider embeddingProvider,
            JdbcTemplate jdbc,
            AuditEventService audit,
            Clock clock
    ) {
        this.accessService = accessService;
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
        KnowledgeAccessService.Access access =
                accessService.requireAccess(
                        knowledgeBaseId,
                        user,
                        KnowledgeBaseAccessLevel.VIEWER
                );

        boolean includeDisabled =
                access.administrator()
                        || access.atLeast(
                        KnowledgeBaseAccessLevel.EDITOR
                );

        String visibilityPredicate =
                includeDisabled
                        ? ""
                        : " and document.enabled = true ";

        Counts counts =
                jdbc.queryForObject(
                        """
                        select
                            count(*) as total_documents,
                            count(*) filter (
                                where document.enabled
                            ) as enabled_documents,

                            count(*) filter (
                                where document.enabled
                                  and document.current_version_id is not null
                                  and exists (
                                      select 1
                                      from knowledge_document_chunks chunk
                                      join knowledge_ingestion_jobs active_job
                                        on active_job.document_version_id =
                                               chunk.document_version_id
                                       and active_job.document_id =
                                               chunk.document_id
                                       and active_job.knowledge_base_id =
                                               chunk.knowledge_base_id
                                       and active_job.organization_id =
                                               chunk.organization_id
                                       and active_job.index_generation =
                                               chunk.index_generation
                                      where chunk.organization_id =
                                                document.organization_id
                                        and chunk.knowledge_base_id =
                                                document.knowledge_base_id
                                        and chunk.document_id =
                                                document.id
                                        and chunk.document_version_id =
                                                document.current_version_id
                                        and chunk.embedding_model = ?
                                  )
                            ) as searchable_documents,

                            count(*) filter (
                                where job.status = 'PENDING'
                            ) as pending_documents,

                            count(*) filter (
                                where job.status in (
                                    'VALIDATING',
                                    'EXTRACTING',
                                    'CHUNKING'
                                )
                            ) as processing_documents,

                            count(*) filter (
                                where job.status = 'FAILED'
                            ) as failed_documents,

                            count(*) filter (
                                where document.enabled
                                  and document.current_version_id is not null
                                  and exists (
                                      select 1
                                      from knowledge_document_chunks stale_chunk
                                      join knowledge_ingestion_jobs stale_job
                                        on stale_job.document_version_id =
                                               stale_chunk.document_version_id
                                       and stale_job.document_id =
                                               stale_chunk.document_id
                                       and stale_job.knowledge_base_id =
                                               stale_chunk.knowledge_base_id
                                       and stale_job.organization_id =
                                               stale_chunk.organization_id
                                       and stale_job.index_generation =
                                               stale_chunk.index_generation
                                      where stale_chunk.organization_id =
                                                document.organization_id
                                        and stale_chunk.knowledge_base_id =
                                                document.knowledge_base_id
                                        and stale_chunk.document_id =
                                                document.id
                                        and stale_chunk.document_version_id =
                                                document.current_version_id
                                        and stale_chunk.embedding_model <> ?
                                  )
                                  and not exists (
                                      select 1
                                      from knowledge_document_chunks current_chunk
                                      join knowledge_ingestion_jobs current_job
                                        on current_job.document_version_id =
                                               current_chunk.document_version_id
                                       and current_job.document_id =
                                               current_chunk.document_id
                                       and current_job.knowledge_base_id =
                                               current_chunk.knowledge_base_id
                                       and current_job.organization_id =
                                               current_chunk.organization_id
                                       and current_job.index_generation =
                                               current_chunk.index_generation
                                      where current_chunk.organization_id =
                                                document.organization_id
                                        and current_chunk.knowledge_base_id =
                                                document.knowledge_base_id
                                        and current_chunk.document_id =
                                                document.id
                                        and current_chunk.document_version_id =
                                                document.current_version_id
                                        and current_chunk.embedding_model = ?
                                  )
                            ) as stale_embedding_documents,

                            coalesce(
                                (
                                    select count(*)
                                    from knowledge_document_chunks chunk
                                    join knowledge_documents active_document
                                      on active_document.id =
                                             chunk.document_id
                                     and active_document.knowledge_base_id =
                                             chunk.knowledge_base_id
                                     and active_document.organization_id =
                                             chunk.organization_id
                                     and active_document.current_version_id =
                                             chunk.document_version_id
                                    join knowledge_ingestion_jobs active_job
                                      on active_job.document_version_id =
                                             chunk.document_version_id
                                     and active_job.document_id =
                                             chunk.document_id
                                     and active_job.knowledge_base_id =
                                             chunk.knowledge_base_id
                                     and active_job.organization_id =
                                             chunk.organization_id
                                     and active_job.index_generation =
                                             chunk.index_generation
                                    where chunk.organization_id = ?
                                      and chunk.knowledge_base_id = ?
                                      and (
                                          ?
                                          or active_document.enabled
                                      )
                                ),
                                0
                            ) as active_chunks
                        from knowledge_documents document
                        left join knowledge_ingestion_jobs job
                          on job.document_version_id =
                                 document.current_version_id
                         and job.document_id =
                                 document.id
                         and job.knowledge_base_id =
                                 document.knowledge_base_id
                         and job.organization_id =
                                 document.organization_id
                        where document.organization_id = ?
                          and document.knowledge_base_id = ?
                        """
                                + visibilityPredicate,
                        this::mapCounts,
                        embeddingProvider.model(),
                        embeddingProvider.model(),
                        embeddingProvider.model(),
                        user.getOrganizationId(),
                        knowledgeBaseId,
                        includeDisabled,
                        user.getOrganizationId(),
                        knowledgeBaseId
                );

        if (counts == null) {
            throw new IllegalStateException(
                    "Knowledge health query returned no row"
            );
        }

        KnowledgeHealthState state =
                resolveState(
                        access,
                        counts
                );

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
        accessService.requireAccess(
                knowledgeBaseId,
                user,
                KnowledgeBaseAccessLevel.EDITOR
        );

        KnowledgeDocumentEntity document =
                documents.findForUpdate(
                        documentId,
                        knowledgeBaseId,
                        user.getOrganizationId()
                )
                .orElseThrow(
                        this::notFound
                );

        if (document.getCurrentVersionId() == null) {
            throw new ConflictException(
                    "У документа нет текущей версии."
            );
        }

        JobRow job =
                jdbc.query(
                        """
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
                )
                .stream()
                .findFirst()
                .orElseThrow(
                        this::notFound
                );

        if (PROCESSING_STATUSES.contains(
                job.status()
        )) {
            throw new ConflictException(
                    "Reindex уже выполняется."
            );
        }

        Instant now =
                clock.instant();

        int updated =
                jdbc.update(
                        """
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
            throw new ConflictException(
                    "Состояние ingestion job изменилось."
            );
        }

        audit.record(
                user,
                user.getOrganizationId(),
                AuditEventType.KNOWLEDGE_REINDEX_REQUESTED,
                Map.of(
                        "knowledgeBaseId",
                        knowledgeBaseId.toString(),
                        "documentId",
                        documentId.toString(),
                        "documentVersionId",
                        document.getCurrentVersionId().toString(),
                        "ingestionJobId",
                        job.id().toString()
                )
        );

        return new KnowledgeReindexResponse(
                knowledgeBaseId,
                documentId,
                document.getCurrentVersionId(),
                job.id(),
                KnowledgeIngestionStatus.PENDING,
                now
        );
    }

    private static KnowledgeHealthState resolveState(
            KnowledgeAccessService.Access access,
            Counts counts
    ) {
        if (!access.knowledgeBase().isEnabled()) {
            return KnowledgeHealthState.DISABLED;
        }

        if (counts.totalDocuments() == 0) {
            return KnowledgeHealthState.EMPTY;
        }

        if (counts.failedDocuments() > 0
                || counts.staleEmbeddingDocuments() > 0) {
            return KnowledgeHealthState.DEGRADED;
        }

        if (counts.pendingDocuments() > 0
                || counts.processingDocuments() > 0) {
            return KnowledgeHealthState.INDEXING;
        }

        return counts.searchableDocuments()
                == counts.enabledDocuments()
                ? KnowledgeHealthState.HEALTHY
                : KnowledgeHealthState.INDEXING;
    }

    private Counts mapCounts(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new Counts(
                resultSet.getLong(
                        "total_documents"
                ),
                resultSet.getLong(
                        "enabled_documents"
                ),
                resultSet.getLong(
                        "searchable_documents"
                ),
                resultSet.getLong(
                        "pending_documents"
                ),
                resultSet.getLong(
                        "processing_documents"
                ),
                resultSet.getLong(
                        "failed_documents"
                ),
                resultSet.getLong(
                        "stale_embedding_documents"
                ),
                resultSet.getLong(
                        "active_chunks"
                )
        );
    }

    private JobRow mapJob(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new JobRow(
                resultSet.getObject(
                        "id",
                        UUID.class
                ),
                resultSet.getString(
                        "status"
                )
        );
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException(
                "Ресурс Knowledge не найден."
        );
    }

    private record JobRow(
            UUID id,
            String status
    ) {
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
