package ru.safeai.gateway.knowledge.retrieval;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.knowledge.embedding.PgVectorSupport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeRetrievalRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public KnowledgeRetrievalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeRetrievalHit> hybridSearch(
            UUID organizationId,
            UUID knowledgeBaseId,
            UUID userId,
            boolean administrator,
            String query,
            float[] queryEmbedding,
            String embeddingModel,
            int topK,
            int candidateLimit,
            int rrfK
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("knowledgeBaseId", knowledgeBaseId)
                .addValue("userId", userId)
                .addValue("administrator", administrator)
                .addValue("query", query)
                .addValue("embedding", PgVectorSupport.encode(queryEmbedding))
                .addValue("embeddingModel", embeddingModel)
                .addValue("topK", topK)
                .addValue("candidateLimit", candidateLimit)
                .addValue("rrfK", rrfK);

        return jdbc.query("""
                with query_parameters as (
                    select
                        websearch_to_tsquery('simple', :query) as text_query,
                        cast(:embedding as vector) as query_embedding
                ),
                allowed_chunks as materialized (
                    select
                        chunk.id as chunk_id,
                        chunk.document_id,
                        chunk.document_version_id,
                        document.name as document_name,
                        version.version_number,
                        chunk.ordinal as chunk_ordinal,
                        chunk.content,
                        chunk.page_from,
                        chunk.page_to,
                        chunk.heading,
                        chunk.content_sha256,
                        chunk.search_vector,
                        chunk.embedding
                    from knowledge_document_chunks chunk
                    join knowledge_bases knowledge_base
                      on knowledge_base.id = chunk.knowledge_base_id
                     and knowledge_base.organization_id = chunk.organization_id
                    join knowledge_documents document
                      on document.id = chunk.document_id
                     and document.knowledge_base_id = chunk.knowledge_base_id
                     and document.organization_id = chunk.organization_id
                     and document.current_version_id = chunk.document_version_id
                    join knowledge_document_versions version
                      on version.id = chunk.document_version_id
                     and version.document_id = chunk.document_id
                     and version.knowledge_base_id = chunk.knowledge_base_id
                     and version.organization_id = chunk.organization_id
                    join knowledge_ingestion_jobs job
                      on job.document_version_id = chunk.document_version_id
                     and job.document_id = chunk.document_id
                     and job.knowledge_base_id = chunk.knowledge_base_id
                     and job.organization_id = chunk.organization_id
                     and job.index_generation = chunk.index_generation
                    where chunk.organization_id = :organizationId
                      and chunk.knowledge_base_id = :knowledgeBaseId
                      and chunk.embedding_model = :embeddingModel
                      and knowledge_base.enabled
                      and document.enabled
                      and (
                          :administrator
                          or knowledge_base.visibility = 'ORGANIZATION'
                          or exists (
                              select 1
                              from knowledge_base_memberships membership
                              where membership.knowledge_base_id =
                                    knowledge_base.id
                                and membership.organization_id =
                                    knowledge_base.organization_id
                                and membership.user_id = :userId
                          )
                      )
                ),
                lexical_candidates as (
                    select
                        allowed.chunk_id,
                        row_number() over (
                            order by
                                ts_rank_cd(
                                    allowed.search_vector,
                                    parameters.text_query,
                                    32
                                ) desc,
                                allowed.chunk_id
                        )::integer as lexical_rank,
                        ts_rank_cd(
                            allowed.search_vector,
                            parameters.text_query,
                            32
                        )::real as lexical_score
                    from allowed_chunks allowed
                    cross join query_parameters parameters
                    where allowed.search_vector @@ parameters.text_query
                    order by lexical_score desc, allowed.chunk_id
                    limit :candidateLimit
                ),
                semantic_candidates as (
                    select
                        allowed.chunk_id,
                        row_number() over (
                            order by
                                allowed.embedding <=>
                                    parameters.query_embedding,
                                allowed.chunk_id
                        )::integer as semantic_rank,
                        greatest(
                            -1.0,
                            least(
                                1.0,
                                1.0 - (
                                    allowed.embedding <=>
                                    parameters.query_embedding
                                )
                            )
                        )::real as cosine_similarity
                    from allowed_chunks allowed
                    cross join query_parameters parameters
                    order by
                        allowed.embedding <=> parameters.query_embedding,
                        allowed.chunk_id
                    limit :candidateLimit
                ),
                fused_candidates as (
                    select
                        candidate.chunk_id,
                        max(candidate.lexical_rank) as lexical_rank,
                        max(candidate.semantic_rank) as semantic_rank,
                        max(candidate.lexical_score) as lexical_score,
                        max(candidate.cosine_similarity) as cosine_similarity,
                        sum(candidate.rrf_score) as fused_score
                    from (
                        select
                            lexical.chunk_id,
                            lexical.lexical_rank,
                            null::integer as semantic_rank,
                            lexical.lexical_score,
                            null::real as cosine_similarity,
                            1.0 / (:rrfK + lexical.lexical_rank) as rrf_score
                        from lexical_candidates lexical
                        union all
                        select
                            semantic.chunk_id,
                            null::integer as lexical_rank,
                            semantic.semantic_rank,
                            null::real as lexical_score,
                            semantic.cosine_similarity,
                            1.0 / (:rrfK + semantic.semantic_rank) as rrf_score
                        from semantic_candidates semantic
                    ) candidate
                    group by candidate.chunk_id
                )
                select
                    allowed.chunk_id,
                    allowed.document_id,
                    allowed.document_version_id,
                    allowed.document_name,
                    allowed.version_number,
                    allowed.chunk_ordinal,
                    allowed.content,
                    allowed.page_from,
                    allowed.page_to,
                    allowed.heading,
                    allowed.content_sha256,
                    fused.fused_score,
                    fused.lexical_rank,
                    fused.semantic_rank,
                    fused.lexical_score,
                    fused.cosine_similarity
                from fused_candidates fused
                join allowed_chunks allowed
                  on allowed.chunk_id = fused.chunk_id
                order by fused.fused_score desc, allowed.chunk_id
                limit :topK
                """, parameters, this::mapHit);
    }

    private KnowledgeRetrievalHit mapHit(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new KnowledgeRetrievalHit(
                resultSet.getObject("chunk_id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getObject("document_version_id", UUID.class),
                resultSet.getString("document_name"),
                resultSet.getInt("version_number"),
                resultSet.getInt("chunk_ordinal"),
                resultSet.getString("content"),
                nullableInteger(resultSet, "page_from"),
                nullableInteger(resultSet, "page_to"),
                resultSet.getString("heading"),
                resultSet.getDouble("fused_score"),
                nullableInteger(resultSet, "lexical_rank"),
                nullableInteger(resultSet, "semantic_rank"),
                nullableFloat(resultSet, "lexical_score"),
                nullableFloat(resultSet, "cosine_similarity"),
                resultSet.getString("content_sha256")
        );
    }

    private static Integer nullableInteger(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Float nullableFloat(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        float value = resultSet.getFloat(column);
        return resultSet.wasNull() ? null : value;
    }
}
