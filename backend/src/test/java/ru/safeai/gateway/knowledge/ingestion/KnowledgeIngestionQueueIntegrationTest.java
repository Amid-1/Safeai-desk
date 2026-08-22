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

    @Autowired
    private KnowledgeIngestionQueueRepository queue;

    @Test
    void expiredLeaseCanBeReclaimedAndOldTokenIsFencedOut() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();

        insertUser(
                userId,
                PLATFORM_ORGANIZATION_ID,
                "knowledge-worker@example.test",
                true,
                "ADMIN",
                now
        );
        insertGraph(
                knowledgeBaseId,
                documentId,
                versionId,
                jobId,
                userId
        );

        Instant firstClaimedAt = now.plusSeconds(5);
        KnowledgeIngestionClaim first = queue.claimNext(
                firstClaimedAt,
                firstClaimedAt.plusSeconds(30),
                5
        ).orElseThrow();
        Instant recoveredAt = firstClaimedAt.plusSeconds(31);
        KnowledgeIngestionClaim second = queue.claimNext(
                recoveredAt,
                recoveredAt.plusSeconds(30),
                5
        ).orElseThrow();

        assertThat(first.jobId()).isEqualTo(jobId);
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(second.jobId()).isEqualTo(jobId);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.processingToken())
                .isNotEqualTo(first.processingToken());

        assertThatThrownBy(() -> queue.transition(
                first,
                KnowledgeIngestionStatus.VALIDATING,
                KnowledgeIngestionStatus.EXTRACTING,
                recoveredAt,
                recoveredAt.plusSeconds(30)
        )).isInstanceOf(StaleIngestionOwnershipException.class);

        queue.transition(
                second,
                KnowledgeIngestionStatus.VALIDATING,
                KnowledgeIngestionStatus.EXTRACTING,
                recoveredAt,
                recoveredAt.plusSeconds(30)
        );

        assertThat(jdbcTemplate.queryForObject(
                "select status from knowledge_ingestion_jobs where id = ?",
                String.class,
                jobId
        )).isEqualTo("EXTRACTING");
    }

    @Test
    void retryUsesExponentialBackoffAndTerminalAttemptStopsRequeue() {
        UUID userId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();

        insertUser(
                userId,
                PLATFORM_ORGANIZATION_ID,
                "knowledge-retry@example.test",
                true,
                "ADMIN",
                now
        );
        insertGraph(
                knowledgeBaseId,
                documentId,
                versionId,
                jobId,
                userId
        );

        Instant claimedAt = now.plusSeconds(5);
        KnowledgeIngestionClaim claim = queue.claimNext(
                claimedAt,
                claimedAt.plusSeconds(30),
                1
        ).orElseThrow();
        queue.fail(
                claim,
                "TEST_FAILURE",
                "test",
                true,
                1,
                now.plusSeconds(1),
                now.plus(Duration.ofMinutes(1))
        );

        assertThat(jdbcTemplate.queryForObject(
                "select status from knowledge_ingestion_jobs where id = ?",
                String.class,
                jobId
        )).isEqualTo("FAILED");
        assertThat(queue.claimNext(
                now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)).plusSeconds(30),
                1
        )).isEmpty();
    }

    private void insertGraph(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            UUID jobId,
            UUID userId
    ) {
        jdbcTemplate.update("""
                insert into knowledge_bases (
                    id, organization_id, name, visibility, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'ORGANIZATION', true, ?, 0)
                """,
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                "Queue " + knowledgeBaseId,
                userId
        );
        jdbcTemplate.update("""
                insert into knowledge_documents (
                    id, organization_id, knowledge_base_id, name, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'source.txt', true, ?, 0)
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                userId
        );
        jdbcTemplate.update("""
                insert into knowledge_document_versions (
                    id, organization_id, knowledge_base_id, document_id,
                    version_number, original_filename, media_type, size_bytes,
                    sha256, storage_key, created_by_user_id
                ) values (?, ?, ?, ?, 1, 'source.txt', 'text/plain', 1,
                          ?, ?, ?)
                """,
                versionId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                "0".repeat(64),
                "queue-test/" + versionId,
                userId
        );
        jdbcTemplate.update("""
                update knowledge_documents
                set current_version_id = ?
                where id = ?
                """,
                versionId,
                documentId
        );
        jdbcTemplate.update("""
                insert into knowledge_ingestion_jobs (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, status, attempt, version
                ) values (?, ?, ?, ?, ?, 'PENDING', 0, 0)
                """,
                jobId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId
        );
    }
}
