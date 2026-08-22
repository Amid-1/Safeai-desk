package ru.safeai.gateway.knowledge.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.embedding.PgVectorSupport;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeChunkPersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeEmbeddingProvider embeddingProvider;
    private final AuditEventService audit;

    public KnowledgeChunkPersistenceService(
            JdbcTemplate jdbcTemplate,
            KnowledgeEmbeddingProvider embeddingProvider,
            AuditEventService audit
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
        this.audit = audit;
    }

    @Transactional
    public void replaceChunksAndComplete(
            KnowledgeIngestionClaim claim,
            ExtractedDocument extracted,
            String chunkerVersion,
            List<EmbeddedKnowledgeChunk> chunks,
            Instant now
    ) {
        lockOwnedJob(claim, now);

        jdbcTemplate.batchUpdate("""
                insert into knowledge_document_chunks (
                    id,
                    organization_id,
                    knowledge_base_id,
                    document_id,
                    document_version_id,
                    index_generation,
                    ordinal,
                    content,
                    content_sha256,
                    estimated_tokens,
                    page_from,
                    page_to,
                    heading,
                    extractor_version,
                    chunker_version,
                    embedding_model,
                    embedding
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector
                )
                """,
                chunks,
                100,
                (PreparedStatement statement, EmbeddedKnowledgeChunk value) -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, claim.organizationId());
                    statement.setObject(3, claim.knowledgeBaseId());
                    statement.setObject(4, claim.documentId());
                    statement.setObject(5, claim.documentVersionId());
                    statement.setObject(6, claim.processingToken());
                    statement.setInt(7, value.chunk().ordinal());
                    statement.setString(8, value.chunk().content());
                    statement.setString(9, value.chunk().contentSha256());
                    statement.setInt(10, value.chunk().estimatedTokens());
                    setNullableInteger(statement, 11, value.chunk().pageFrom());
                    setNullableInteger(statement, 12, value.chunk().pageTo());
                    setNullableHeading(statement, 13, value.chunk().heading());
                    statement.setString(14, extracted.extractorVersion());
                    statement.setString(15, chunkerVersion);
                    statement.setString(16, embeddingProvider.model());
                    statement.setString(
                            17,
                            PgVectorSupport.encode(value.embedding())
                    );
                }
        );

        int updated = jdbcTemplate.update("""
                update knowledge_ingestion_jobs
                set status = 'READY',
                    processing_token = null,
                    claimed_at = null,
                    lease_until = null,
                    error_code = null,
                    error_message = null,
                    extractor_version = ?,
                    chunker_version = ?,
                    embedding_model = ?,
                    index_generation = ?,
                    extracted_char_count = ?,
                    chunk_count = ?,
                    finished_at = ?,
                    updated_at = ?,
                    version = version + 1
                where id = ?
                  and status = 'CHUNKING'
                  and processing_token = ?
                  and lease_until >= ?
                """,
                extracted.extractorVersion(),
                chunkerVersion,
                embeddingProvider.model(),
                claim.processingToken(),
                extracted.characterCount(),
                chunks.size(),
                Timestamp.from(now),
                Timestamp.from(now),
                claim.jobId(),
                claim.processingToken(),
                Timestamp.from(now)
        );
        if (updated != 1) {
            throw new StaleIngestionOwnershipException();
        }

        audit.recordSystem(
                claim.organizationId(),
                AuditEventType.KNOWLEDGE_INGESTION_READY,
                Map.of(
                        "knowledgeBaseId", claim.knowledgeBaseId().toString(),
                        "documentId", claim.documentId().toString(),
                        "documentVersionId",
                        claim.documentVersionId().toString(),
                        "ingestionJobId", claim.jobId().toString(),
                        "attempt", claim.attempt(),
                        "extractorVersion", extracted.extractorVersion(),
                        "chunkerVersion", chunkerVersion,
                        "embeddingModel", embeddingProvider.model(),
                        "chunkCount", chunks.size()
                )
        );
    }

    private void lockOwnedJob(
            KnowledgeIngestionClaim claim,
            Instant now
    ) {
        List<UUID> rows = jdbcTemplate.queryForList("""
                select id
                from knowledge_ingestion_jobs
                where id = ?
                  and status = 'CHUNKING'
                  and processing_token = ?
                  and lease_until >= ?
                for update
                """,
                UUID.class,
                claim.jobId(),
                claim.processingToken(),
                Timestamp.from(now)
        );
        if (rows.size() != 1) {
            throw new StaleIngestionOwnershipException();
        }
    }

    private static void setNullableInteger(
            PreparedStatement statement,
            int index,
            Integer value
    ) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableHeading(
            PreparedStatement statement,
            int index,
            String value
    ) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
