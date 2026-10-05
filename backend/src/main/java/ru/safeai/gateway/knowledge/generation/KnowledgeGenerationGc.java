package ru.safeai.gateway.knowledge.generation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Conservative generation retention.
 *
 * <p>Searchable generations are never collected. Retrieval evidence remains a
 * hard pin. Reindex history and superseded document-version history have
 * deliberately separate retention counts so an old document version is not
 * retained forever merely because it has only one generation.</p>
 */
@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeGenerationGc {

    private final JdbcTemplate jdbc;

    public KnowledgeGenerationGc(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc не должен быть null");
    }

    /**
     * Backwards-compatible entry point. Superseded versions are eligible after
     * the age/evidence checks; keepLatest applies only to reindex history of the
     * currently searchable document version.
     */
    @Transactional(timeout = 30)
    public int collect(Duration retention, int keepLatest, int batchSize) {
        return collect(retention, keepLatest, 0, batchSize);
    }

    /** One bounded transaction; SKIP LOCKED permits safe multi-node GC. */
    @Transactional(timeout = 30)
    public int collect(
            Duration retention,
            int keepLatestReindex,
            int keepLatestSupersededVersions,
            int batchSize
    ) {
        validate(
                retention,
                keepLatestReindex,
                keepLatestSupersededVersions,
                batchSize
        );

        /*
         * ACTIVE means "published generation of this document version", not
         * necessarily "currently searchable document version". Once a newer
         * version is atomically published, retire ACTIVE generations belonging
         * to superseded versions. Disabled documents remain recoverable because
         * publication identity does not depend on enabled state.
         */
        jdbc.update("""
                update knowledge_index_generations g
                set state = 'RETIRED',
                    retired_at = clock_timestamp()
                where (g.document_version_id, g.index_generation) in (
                    select x.document_version_id, x.index_generation
                    from knowledge_index_generations x
                    join knowledge_documents d
                      on d.id = x.document_id
                     and d.knowledge_base_id = x.knowledge_base_id
                     and d.organization_id = x.organization_id
                    where x.state = 'ACTIVE'
                      and d.searchable_version_id is distinct from x.document_version_id
                    order by x.document_id, x.document_version_id, x.index_generation
                    limit ?
                    for update of x skip locked
                )
                """, batchSize);

        var candidates = jdbc.query("""
                select g.document_version_id, g.index_generation
                from knowledge_index_generations g
                join knowledge_document_versions candidate_version
                  on candidate_version.id = g.document_version_id
                 and candidate_version.document_id = g.document_id
                 and candidate_version.knowledge_base_id = g.knowledge_base_id
                 and candidate_version.organization_id = g.organization_id
                join knowledge_documents document
                  on document.id = g.document_id
                 and document.knowledge_base_id = g.knowledge_base_id
                 and document.organization_id = g.organization_id
                where g.state = 'RETIRED'
                  and g.retired_at <= clock_timestamp() - (? * interval '1 second')
                  and not exists (
                      select 1
                      from knowledge_document_chunks chunk
                      join knowledge_retrieval_hits hit
                        on hit.chunk_id = chunk.id
                      where chunk.document_version_id = g.document_version_id
                        and chunk.index_generation = g.index_generation
                  )
                  and (
                      (
                          document.searchable_version_id = g.document_version_id
                          and (
                              select count(*)
                              from knowledge_index_generations newer
                              where newer.document_version_id = g.document_version_id
                                and newer.state <> 'COLLECTED'
                                and (newer.published_at, newer.index_generation)
                                    > (g.published_at, g.index_generation)
                          ) >= ?
                      )
                      or
                      (
                          document.searchable_version_id is distinct from g.document_version_id
                          and (
                              select count(distinct newer_version.id)
                              from knowledge_document_versions newer_version
                              join knowledge_index_generations newer_generation
                                on newer_generation.document_version_id = newer_version.id
                               and newer_generation.document_id = g.document_id
                               and newer_generation.knowledge_base_id = g.knowledge_base_id
                               and newer_generation.organization_id = g.organization_id
                               and newer_generation.state <> 'COLLECTED'
                              where newer_version.document_id = g.document_id
                                and newer_version.version_number > candidate_version.version_number
                                and newer_version.id is distinct from document.searchable_version_id
                          ) >= ?
                      )
                  )
                order by g.retired_at, g.document_id, candidate_version.version_number,
                         g.published_at, g.index_generation
                limit ?
                for update of g skip locked
                """, (rs, n) -> new Generation(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class)
                ),
                retention.toSeconds(),
                keepLatestReindex,
                keepLatestSupersededVersions,
                batchSize
        );

        for (var generation : candidates) {
            jdbc.update("""
                    update knowledge_index_generations
                    set state = 'COLLECTING'
                    where document_version_id = ?
                      and index_generation = ?
                      and state = 'RETIRED'
                    """, generation.version(), generation.generation());

            jdbc.update("""
                    delete from knowledge_document_chunks
                    where document_version_id = ?
                      and index_generation = ?
                    """, generation.version(), generation.generation());

            jdbc.update("""
                    update knowledge_index_generations
                    set state = 'COLLECTED',
                        collected_at = clock_timestamp()
                    where document_version_id = ?
                      and index_generation = ?
                      and state = 'COLLECTING'
                    """, generation.version(), generation.generation());
        }

        return candidates.size();
    }


    static void validate(
            Duration retention,
            int keepLatestReindex,
            int keepLatestSupersededVersions,
            int batchSize
    ) {
        if (retention == null
                || retention.compareTo(Duration.ofDays(1)) < 0
                || retention.compareTo(Duration.ofDays(36500)) > 0
                || keepLatestReindex < 1
                || keepLatestReindex > 100
                || keepLatestSupersededVersions < 0
                || keepLatestSupersededVersions > 100
                || batchSize < 1
                || batchSize > 100) {
            throw new IllegalArgumentException(
                    "Generation GC requires retention 1..36500 days, "
                            + "keepLatestReindex 1..100, "
                            + "keepLatestSupersededVersions 0..100, "
                            + "batchSize 1..100"
            );
        }
    }

    private record Generation(UUID version, UUID generation) {
    }
}
