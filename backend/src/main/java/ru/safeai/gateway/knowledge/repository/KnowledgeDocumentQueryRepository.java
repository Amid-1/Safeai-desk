package ru.safeai.gateway.knowledge.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentResponse;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeDocumentQueryRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeDocumentQueryRepository(
            JdbcTemplate jdbc
    ) {
        this.jdbc = jdbc;
    }

    public KnowledgeDocumentPageResponse list(
            UUID organizationId,
            UUID knowledgeBaseId,
            int page,
            int size,
            boolean includeDisabled
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );
        Objects.requireNonNull(
                knowledgeBaseId,
                "knowledgeBaseId не должен быть null"
        );

        requireValidPage(
                page,
                size
        );

        String enabledPredicate =
                includeDisabled
                        ? ""
                        : " and document.enabled = true ";

        long total =
                countDocuments(
                        organizationId,
                        knowledgeBaseId,
                        enabledPredicate
                );

        long offset =
                Math.multiplyFull(
                        page,
                        size
                );

        List<KnowledgeDocumentResponse> content =
                jdbc.query(
                        """
                        select
                            document.id,
                            document.knowledge_base_id,
                            document.name,
                            document.enabled,
                            document.version as document_version,
                            document.current_version_id,
                            document.created_at,
                            document.updated_at,
                            version.version_number,
                            version.original_filename,
                            version.media_type,
                            version.size_bytes,
                            job.status as ingestion_status
                        from knowledge_documents document
                        left join knowledge_document_versions version
                          on version.id = document.current_version_id
                         and version.document_id = document.id
                         and version.knowledge_base_id =
                             document.knowledge_base_id
                         and version.organization_id =
                             document.organization_id
                        left join knowledge_ingestion_jobs job
                          on job.document_version_id = version.id
                         and job.document_id = document.id
                         and job.knowledge_base_id =
                             document.knowledge_base_id
                         and job.organization_id =
                             document.organization_id
                        where document.organization_id = ?
                          and document.knowledge_base_id = ?
                        """
                                + enabledPredicate
                                + """
                        order by
                            document.updated_at desc,
                            document.id desc
                        limit ? offset ?
                        """,
                        this::map,
                        organizationId,
                        knowledgeBaseId,
                        size,
                        offset
                );

        return new KnowledgeDocumentPageResponse(
                content,
                page,
                size,
                total,
                totalPages(
                        total,
                        size
                )
        );
    }

    private long countDocuments(
            UUID organizationId,
            UUID knowledgeBaseId,
            String enabledPredicate
    ) {
        Long value =
                jdbc.queryForObject(
                        """
                        select count(*)
                        from knowledge_documents document
                        where document.organization_id = ?
                          and document.knowledge_base_id = ?
                        """
                                + enabledPredicate,
                        Long.class,
                        organizationId,
                        knowledgeBaseId
                );

        return Objects.requireNonNull(
                value,
                "COUNT(*) вернул null для knowledge_documents"
        );
    }

    private KnowledgeDocumentResponse map(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        String status =
                resultSet.getString(
                        "ingestion_status"
                );

        return new KnowledgeDocumentResponse(
                resultSet.getObject(
                        "id",
                        UUID.class
                ),
                resultSet.getObject(
                        "knowledge_base_id",
                        UUID.class
                ),
                resultSet.getString(
                        "name"
                ),
                resultSet.getBoolean(
                        "enabled"
                ),
                resultSet.getLong(
                        "document_version"
                ),
                resultSet.getObject(
                        "current_version_id",
                        UUID.class
                ),
                nullableVersionNumber(
                        resultSet
                ),
                resultSet.getString(
                        "original_filename"
                ),
                resultSet.getString(
                        "media_type"
                ),
                sizeBytesOrZero(
                        resultSet
                ),
                status == null
                        ? null
                        : KnowledgeIngestionStatus.valueOf(
                        status
                ),
                resultSet.getTimestamp(
                        "created_at"
                ).toInstant(),
                resultSet.getTimestamp(
                        "updated_at"
                ).toInstant()
        );
    }

    private static Integer nullableVersionNumber(
            ResultSet resultSet
    ) throws SQLException {
        int value =
                resultSet.getInt(
                        "version_number"
                );

        return resultSet.wasNull()
                ? null
                : value;
    }

    private static long sizeBytesOrZero(
            ResultSet resultSet
    ) throws SQLException {
        long value =
                resultSet.getLong(
                        "size_bytes"
                );

        return resultSet.wasNull()
                ? 0L
                : value;
    }

    private static int totalPages(
            long total,
            int size
    ) {
        if (total == 0L) {
            return 0;
        }

        long pages =
                total / size
                        + (
                        total % size == 0L
                                ? 0L
                                : 1L
                );

        return Math.toIntExact(
                pages
        );
    }

    private static void requireValidPage(
            int page,
            int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "page не должен быть отрицательным"
            );
        }

        if (size < 1) {
            throw new IllegalArgumentException(
                    "size должен быть положительным"
            );
        }
    }
}