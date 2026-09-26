package ru.safeai.gateway.knowledge.generation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/** Conservative retention: a generation with any retrieval evidence is retained in full. */
@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeGenerationGc {
    private final JdbcTemplate jdbc;

    public KnowledgeGenerationGc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One bounded transaction; SKIP LOCKED permits multiple nodes and respects retrieval pins. */
    @Transactional(timeout = 30)
    public int collect(Duration retention, int keepLatest, int batchSize) {
        validate(retention, keepLatest, batchSize);
        // Retire generations of superseded document versions. Disabled documents stay recoverable.
        jdbc.update("""
            update knowledge_index_generations g set state='RETIRED', retired_at=clock_timestamp()
            where (g.document_version_id,g.index_generation) in (
                select x.document_version_id,x.index_generation from knowledge_index_generations x
                where x.state='ACTIVE' and not exists (
                    select 1 from knowledge_ingestion_jobs j join knowledge_documents d
                    on d.id=j.document_id and d.current_version_id=j.document_version_id
                    where j.document_version_id=x.document_version_id and j.index_generation=x.index_generation)
                order by x.document_version_id,x.index_generation
                limit ? for update of x skip locked)
            """, batchSize);
        var candidates = jdbc.query("""
            select g.document_version_id,g.index_generation from knowledge_index_generations g
            where g.state='RETIRED'
              and g.retired_at <= clock_timestamp() - (? * interval '1 second')
              and not exists (select 1 from knowledge_ingestion_jobs j join knowledge_documents d
                  on d.id=j.document_id and d.current_version_id=j.document_version_id
                  where j.document_version_id=g.document_version_id and j.index_generation=g.index_generation)
              and not exists (select 1 from knowledge_document_chunks c
                  join knowledge_retrieval_hits h on h.chunk_id=c.id
                  where c.document_version_id=g.document_version_id and c.index_generation=g.index_generation)
              and (select count(*) from knowledge_index_generations newer
                  where newer.document_version_id=g.document_version_id and newer.state <> 'COLLECTED'
                  and (newer.published_at,newer.index_generation) > (g.published_at,g.index_generation)) >= ?
            order by g.retired_at,g.document_version_id,g.index_generation
            limit ? for update of g skip locked
            """, (rs, n) -> new Generation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                retention.toSeconds(), keepLatest, batchSize);
        for (var g : candidates) {
            jdbc.update("""
                update knowledge_index_generations set state='COLLECTING'
                where document_version_id=? and index_generation=? and state='RETIRED'
                """, g.version(), g.generation());
            jdbc.update("delete from knowledge_document_chunks where document_version_id=? and index_generation=?",
                    g.version(), g.generation());
            jdbc.update("""
                update knowledge_index_generations set state='COLLECTED',collected_at=clock_timestamp()
                where document_version_id=? and index_generation=? and state='COLLECTING'
                """, g.version(), g.generation());
        }
        return candidates.size();
    }

    static void validate(Duration retention, int keepLatest, int batchSize) {
        if (retention == null || retention.compareTo(Duration.ofDays(1)) < 0
                || retention.compareTo(Duration.ofDays(36500)) > 0
                || keepLatest < 1 || keepLatest > 100 || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "Generation GC requires retention 1..36500 days, "
                            + "keepLatest 1..100, batchSize 1..100"
            );
        }
    }

    private record Generation(UUID version, UUID generation) {
    }
}
