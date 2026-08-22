package ru.safeai.gateway.knowledge.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class KnowledgeIngestionQueueIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Duration LEASE =
            Duration.ofSeconds(30);

    @Autowired
    private KnowledgeIngestionQueueRepository queue;

    @Test
    void expiredLeaseCanBeReclaimedAndOldTokenIsFencedOut() {
        UUID userId =
                UUID.randomUUID();

        UUID knowledgeBaseId =
                UUID.randomUUID();

        UUID documentId =
                UUID.randomUUID();

        UUID versionId =
                UUID.randomUUID();

        UUID jobId =
                UUID.randomUUID();

        Instant createdAt =
                Instant.now();

        insertUser(
                userId,
                PLATFORM_ORGANIZATION_ID,
                "knowledge-worker@example.test",
                true,
                "ADMIN",
                createdAt
        );

        insertGraph(
                knowledgeBaseId,
                documentId,
                versionId,
                jobId,
                userId
        );

        Instant firstClaimedAt =
                createdAt.plusSeconds(5);

        KnowledgeIngestionClaim first =
                queue.claimNext(
                        firstClaimedAt,
                        firstClaimedAt.plus(LEASE),
                        DEFAULT_MAX_ATTEMPTS
                ).orElseThrow();

        /*
         * Первый lease действует до firstClaimedAt + 30 секунд.
         * Recover выполняем только после его реального истечения.
         */
        Instant recoveredAt =
                firstClaimedAt
                        .plus(LEASE)
                        .plusSeconds(1);

        KnowledgeIngestionClaim second =
                queue.claimNext(
                        recoveredAt,
                        recoveredAt.plus(LEASE),
                        DEFAULT_MAX_ATTEMPTS
                ).orElseThrow();

        assertThat(first.jobId())
                .isEqualTo(jobId);

        assertThat(first.attempt())
                .isEqualTo(1);

        assertThat(second.jobId())
                .isEqualTo(jobId);

        assertThat(second.attempt())
                .isEqualTo(2);

        assertThat(second.processingToken())
                .isNotEqualTo(
                        first.processingToken()
                );

        /*
         * Старый processing token должен быть fenced out.
         */
        assertThatThrownBy(
                () -> queue.transition(
                        first,
                        KnowledgeIngestionStatus.VALIDATING,
                        KnowledgeIngestionStatus.EXTRACTING,
                        recoveredAt,
                        recoveredAt.plus(LEASE)
                )
        )
                .isInstanceOf(
                        StaleIngestionOwnershipException.class
                );

        /*
         * Новый владелец lease продолжает processing.
         */
        queue.transition(
                second,
                KnowledgeIngestionStatus.VALIDATING,
                KnowledgeIngestionStatus.EXTRACTING,
                recoveredAt,
                recoveredAt.plus(LEASE)
        );

        assertThat(
                status(jobId)
        ).isEqualTo(
                "EXTRACTING"
        );

        assertThat(
                attempt(jobId)
        ).isEqualTo(
                2
        );
    }

    @Test
    void retryableFailureWaitsUntilNextAttemptAt() {
        UUID userId =
                UUID.randomUUID();

        UUID knowledgeBaseId =
                UUID.randomUUID();

        UUID documentId =
                UUID.randomUUID();

        UUID versionId =
                UUID.randomUUID();

        UUID jobId =
                UUID.randomUUID();

        Instant createdAt =
                Instant.now();

        insertUser(
                userId,
                PLATFORM_ORGANIZATION_ID,
                "knowledge-retry-backoff@example.test",
                true,
                "ADMIN",
                createdAt
        );

        insertGraph(
                knowledgeBaseId,
                documentId,
                versionId,
                jobId,
                userId
        );

        Instant claimedAt =
                createdAt.plusSeconds(5);

        KnowledgeIngestionClaim first =
                queue.claimNext(
                        claimedAt,
                        claimedAt.plus(LEASE),
                        3
                ).orElseThrow();

        assertThat(first.jobId())
                .isEqualTo(jobId);

        assertThat(first.attempt())
                .isEqualTo(1);

        /*
         * Важно: failureAt должен быть >= started_at/claimed_at.
         */
        Instant failureAt =
                claimedAt.plusSeconds(1);

        Instant nextAttemptAt =
                failureAt.plusSeconds(30);

        queue.fail(
                first,
                "TEMPORARY_FAILURE",
                "temporary failure",
                true,
                3,
                failureAt,
                nextAttemptAt
        );

        assertThat(
                status(jobId)
        ).isEqualTo(
                "PENDING"
        );

        assertThat(
                nextAttemptAt(jobId)
        ).isEqualTo(
                nextAttemptAt
        );

        /*
         * До next_attempt_at job не должен выдаваться worker-у.
         */
        assertThat(
                queue.claimNext(
                        nextAttemptAt.minusMillis(1),
                        nextAttemptAt
                                .minusMillis(1)
                                .plus(LEASE),
                        3
                )
        ).isEmpty();

        /*
         * Начиная с next_attempt_at job снова доступен.
         */
        KnowledgeIngestionClaim second =
                queue.claimNext(
                        nextAttemptAt,
                        nextAttemptAt.plus(LEASE),
                        3
                ).orElseThrow();

        assertThat(second.jobId())
                .isEqualTo(jobId);

        assertThat(second.attempt())
                .isEqualTo(2);

        assertThat(second.processingToken())
                .isNotEqualTo(
                        first.processingToken()
                );
    }

    @Test
    void terminalAttemptIsMarkedFailedAndCannotBeReclaimed() {
        UUID userId =
                UUID.randomUUID();

        UUID knowledgeBaseId =
                UUID.randomUUID();

        UUID documentId =
                UUID.randomUUID();

        UUID versionId =
                UUID.randomUUID();

        UUID jobId =
                UUID.randomUUID();

        Instant createdAt =
                Instant.now();

        insertUser(
                userId,
                PLATFORM_ORGANIZATION_ID,
                "knowledge-terminal@example.test",
                true,
                "ADMIN",
                createdAt
        );

        insertGraph(
                knowledgeBaseId,
                documentId,
                versionId,
                jobId,
                userId
        );

        Instant claimedAt =
                createdAt.plusSeconds(5);

        KnowledgeIngestionClaim claim =
                queue.claimNext(
                        claimedAt,
                        claimedAt.plus(LEASE),
                        1
                ).orElseThrow();

        assertThat(claim.attempt())
                .isEqualTo(1);

        /*
         * Раньше здесь было createdAt + 1 sec,
         * хотя job был claimed в createdAt + 5 sec.
         *
         * Это создавало:
         * finished_at < started_at
         * и закономерно ломало V44 constraint.
         */
        Instant failureAt =
                claimedAt.plusSeconds(1);

        Instant hypotheticalNextAttempt =
                failureAt.plus(
                        Duration.ofMinutes(1)
                );

        queue.fail(
                claim,
                "TEST_FAILURE",
                "test",
                true,
                1,
                failureAt,
                hypotheticalNextAttempt
        );

        assertThat(
                status(jobId)
        ).isEqualTo(
                "FAILED"
        );

        assertThat(
                attempt(jobId)
        ).isEqualTo(
                1
        );

        assertThat(
                finishedAt(jobId)
        )
                .isNotNull()
                .isEqualTo(
                        failureAt
                );

        /*
         * maxAttempts уже исчерпан.
         * Даже спустя большое время FAILED job не возвращается в queue.
         */
        Instant muchLater =
                failureAt.plus(
                        Duration.ofHours(1)
                );

        assertThat(
                queue.claimNext(
                        muchLater,
                        muchLater.plus(LEASE),
                        1
                )
        ).isEmpty();
    }

    private String status(
            UUID jobId
    ) {
        return jdbcTemplate.queryForObject(
                """
                select status
                from knowledge_ingestion_jobs
                where id = ?
                """,
                String.class,
                jobId
        );
    }

    private int attempt(
            UUID jobId
    ) {
        Integer value =
                jdbcTemplate.queryForObject(
                        """
                        select attempt
                        from knowledge_ingestion_jobs
                        where id = ?
                        """,
                        Integer.class,
                        jobId
                );

        if (value == null) {
            throw new IllegalStateException(
                    "knowledge ingestion attempt unexpectedly null"
            );
        }

        return value;
    }

    private Instant nextAttemptAt(
            UUID jobId
    ) {
        return jdbcTemplate.queryForObject(
                """
                select next_attempt_at
                from knowledge_ingestion_jobs
                where id = ?
                """,
                Instant.class,
                jobId
        );
    }

    private Instant finishedAt(
            UUID jobId
    ) {
        return jdbcTemplate.queryForObject(
                """
                select finished_at
                from knowledge_ingestion_jobs
                where id = ?
                """,
                Instant.class,
                jobId
        );
    }

    private void insertGraph(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            UUID jobId,
            UUID userId
    ) {
        jdbcTemplate.update(
                """
                insert into knowledge_bases (
                    id,
                    organization_id,
                    name,
                    visibility,
                    enabled,
                    created_by_user_id,
                    version
                ) values (
                    ?,
                    ?,
                    ?,
                    'ORGANIZATION',
                    true,
                    ?,
                    0
                )
                """,
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                "Queue " + knowledgeBaseId,
                userId
        );

        jdbcTemplate.update(
                """
                insert into knowledge_documents (
                    id,
                    organization_id,
                    knowledge_base_id,
                    name,
                    enabled,
                    created_by_user_id,
                    version
                ) values (
                    ?,
                    ?,
                    ?,
                    'source.txt',
                    true,
                    ?,
                    0
                )
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                userId
        );

        jdbcTemplate.update(
                """
                insert into knowledge_document_versions (
                    id,
                    organization_id,
                    knowledge_base_id,
                    document_id,
                    version_number,
                    original_filename,
                    media_type,
                    size_bytes,
                    sha256,
                    storage_key,
                    created_by_user_id
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    1,
                    'source.txt',
                    'text/plain',
                    1,
                    ?,
                    ?,
                    ?
                )
                """,
                versionId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                "0".repeat(64),
                "queue-test/" + versionId,
                userId
        );

        jdbcTemplate.update(
                """
                update knowledge_documents
                set current_version_id = ?
                where id = ?
                  and knowledge_base_id = ?
                  and organization_id = ?
                """,
                versionId,
                documentId,
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID
        );

        jdbcTemplate.update(
                """
                insert into knowledge_ingestion_jobs (
                    id,
                    organization_id,
                    knowledge_base_id,
                    document_id,
                    document_version_id,
                    status,
                    attempt,
                    version
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'PENDING',
                    0,
                    0
                )
                """,
                jobId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId
        );
    }
}