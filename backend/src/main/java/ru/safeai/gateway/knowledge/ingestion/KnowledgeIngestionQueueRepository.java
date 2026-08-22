package ru.safeai.gateway.knowledge.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class KnowledgeIngestionQueueRepository {

    private static final int MAX_ERROR_MESSAGE_LENGTH =
            2_000;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeIngestionQueueRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Optional<KnowledgeIngestionClaim> claimNext(
            Instant now,
            Instant leaseUntil,
            int maxAttempts
    ) {
        UUID processingToken =
                UUID.randomUUID();

        List<KnowledgeIngestionClaim> claimed =
                jdbcTemplate.query(
                        """
                        with candidate as (
                            select job.id
                            from knowledge_ingestion_jobs job
                            where job.attempt < ?
                              and (
                                  (
                                      job.status = 'PENDING'
                                      and job.next_attempt_at <= ?
                                  )
                                  or (
                                      job.status in (
                                          'VALIDATING',
                                          'EXTRACTING',
                                          'CHUNKING'
                                      )
                                      and job.lease_until < ?
                                  )
                              )
                            order by
                                case
                                    when job.status = 'PENDING'
                                        then 0
                                    else 1
                                end,
                                job.next_attempt_at,
                                job.created_at,
                                job.id
                            for update skip locked
                            limit 1
                        )
                        update knowledge_ingestion_jobs job
                        set status = 'VALIDATING',
                            processing_token = ?,
                            claimed_at = ?,
                            lease_until = ?,
                            next_attempt_at = ?,
                            started_at = coalesce(
                                job.started_at,
                                ?
                            ),
                            finished_at = null,
                            error_code = null,
                            error_message = null,
                            attempt = job.attempt + 1,
                            updated_at = ?,
                            version = job.version + 1
                        from candidate
                        where job.id = candidate.id
                        returning
                            job.id,
                            job.organization_id,
                            job.knowledge_base_id,
                            job.document_id,
                            job.document_version_id,
                            job.processing_token,
                            job.attempt,
                            job.lease_until
                        """,
                        (resultSet, rowNumber) ->
                                mapClaim(
                                        resultSet
                                ),
                        maxAttempts,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        processingToken,
                        Timestamp.from(now),
                        Timestamp.from(leaseUntil),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        Timestamp.from(now)
                );

        return claimed.stream()
                .findFirst();
    }

    @Transactional
    public void transition(
            KnowledgeIngestionClaim claim,
            KnowledgeIngestionStatus expected,
            KnowledgeIngestionStatus target,
            Instant now,
            Instant newLeaseUntil
    ) {
        int updated =
                jdbcTemplate.update(
                        """
                        update knowledge_ingestion_jobs
                        set status = ?,
                            lease_until = ?,
                            updated_at = ?,
                            version = version + 1
                        where id = ?
                          and status = ?
                          and processing_token = ?
                          and lease_until >= ?
                        """,
                        target.name(),
                        Timestamp.from(newLeaseUntil),
                        Timestamp.from(now),
                        claim.jobId(),
                        expected.name(),
                        claim.processingToken(),
                        Timestamp.from(now)
                );

        requireOwnership(
                updated
        );
    }

    @Transactional
    public void renewLease(
            KnowledgeIngestionClaim claim,
            KnowledgeIngestionStatus expected,
            Instant now,
            Instant newLeaseUntil
    ) {
        int updated =
                jdbcTemplate.update(
                        """
                        update knowledge_ingestion_jobs
                        set lease_until = ?,
                            updated_at = ?,
                            version = version + 1
                        where id = ?
                          and status = ?
                          and processing_token = ?
                          and lease_until >= ?
                        """,
                        Timestamp.from(newLeaseUntil),
                        Timestamp.from(now),
                        claim.jobId(),
                        expected.name(),
                        claim.processingToken(),
                        Timestamp.from(now)
                );

        requireOwnership(
                updated
        );
    }

    @Transactional
    public void fail(
            KnowledgeIngestionClaim claim,
            String errorCode,
            String errorMessage,
            boolean retryable,
            int maxAttempts,
            Instant now,
            Instant nextAttemptAt
    ) {
        String message =
                truncateErrorMessage(
                        errorMessage
                );

        boolean retry =
                retryable
                        && claim.attempt()
                        < maxAttempts;

        int updated = retry
                ? scheduleRetry(
                        claim,
                        errorCode,
                        message,
                        now,
                        nextAttemptAt
                )
                : markFailed(
                        claim,
                        errorCode,
                        message,
                        now
                );

        requireOwnership(
                updated
        );
    }

    @Transactional
    public List<ExpiredKnowledgeIngestionJob>
    failExhaustedExpiredAndReturn(
            Instant now,
            int maxAttempts
    ) {
        return jdbcTemplate.query(
                """
                update knowledge_ingestion_jobs
                set status = 'FAILED',
                    processing_token = null,
                    claimed_at = null,
                    lease_until = null,
                    error_code = 'LEASE_EXPIRED',
                    error_message =
                        'Лимит попыток исчерпан после потери lease',
                    finished_at = ?,
                    updated_at = ?,
                    version = version + 1
                where status in (
                    'VALIDATING',
                    'EXTRACTING',
                    'CHUNKING'
                )
                  and lease_until < ?
                  and attempt >= ?
                returning
                    id,
                    organization_id,
                    knowledge_base_id,
                    document_id,
                    document_version_id,
                    attempt
                """,
                (resultSet, rowNumber) ->
                        mapExpired(
                                resultSet
                        ),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                maxAttempts
        );
    }

    private int scheduleRetry(
            KnowledgeIngestionClaim claim,
            String errorCode,
            String message,
            Instant now,
            Instant nextAttemptAt
    ) {
        return jdbcTemplate.update(
                """
                update knowledge_ingestion_jobs
                set status = 'PENDING',
                    processing_token = null,
                    claimed_at = null,
                    lease_until = null,
                    next_attempt_at = ?,
                    error_code = ?,
                    error_message = ?,
                    updated_at = ?,
                    version = version + 1
                where id = ?
                  and status in (
                      'VALIDATING',
                      'EXTRACTING',
                      'CHUNKING'
                  )
                  and processing_token = ?
                  and lease_until >= ?
                """,
                Timestamp.from(nextAttemptAt),
                errorCode,
                message,
                Timestamp.from(now),
                claim.jobId(),
                claim.processingToken(),
                Timestamp.from(now)
        );
    }

    private int markFailed(
            KnowledgeIngestionClaim claim,
            String errorCode,
            String message,
            Instant now
    ) {
        return jdbcTemplate.update(
                """
                update knowledge_ingestion_jobs
                set status = 'FAILED',
                    processing_token = null,
                    claimed_at = null,
                    lease_until = null,
                    error_code = ?,
                    error_message = ?,
                    finished_at = ?,
                    updated_at = ?,
                    version = version + 1
                where id = ?
                  and status in (
                      'VALIDATING',
                      'EXTRACTING',
                      'CHUNKING'
                  )
                  and processing_token = ?
                  and lease_until >= ?
                """,
                errorCode,
                message,
                Timestamp.from(now),
                Timestamp.from(now),
                claim.jobId(),
                claim.processingToken(),
                Timestamp.from(now)
        );
    }

    private static KnowledgeIngestionClaim mapClaim(
            ResultSet resultSet
    ) throws SQLException {
        return new KnowledgeIngestionClaim(
                resultSet.getObject(
                        "id",
                        UUID.class
                ),
                resultSet.getObject(
                        "organization_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "knowledge_base_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "document_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "document_version_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "processing_token",
                        UUID.class
                ),
                resultSet.getInt(
                        "attempt"
                ),
                resultSet.getTimestamp(
                        "lease_until"
                ).toInstant()
        );
    }

    private static ExpiredKnowledgeIngestionJob mapExpired(
            ResultSet resultSet
    ) throws SQLException {
        return new ExpiredKnowledgeIngestionJob(
                resultSet.getObject(
                        "id",
                        UUID.class
                ),
                resultSet.getObject(
                        "organization_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "knowledge_base_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "document_id",
                        UUID.class
                ),
                resultSet.getObject(
                        "document_version_id",
                        UUID.class
                ),
                resultSet.getInt(
                        "attempt"
                )
        );
    }

    private static void requireOwnership(
            int updated
    ) {
        if (updated != 1) {
            throw new StaleIngestionOwnershipException();
        }
    }

    private static String truncateErrorMessage(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return "Неизвестная ошибка ingestion";
        }

        String normalized =
                value.strip();

        return normalized.length()
                <= MAX_ERROR_MESSAGE_LENGTH
                ? normalized
                : normalized.substring(
                        0,
                        MAX_ERROR_MESSAGE_LENGTH
                );
    }
}