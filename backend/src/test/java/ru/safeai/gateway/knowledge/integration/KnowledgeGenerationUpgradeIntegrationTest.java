// backend/src/test/java/ru/safeai/gateway/knowledge/integration/KnowledgeGenerationUpgradeIntegrationTest.java
package ru.safeai.gateway.knowledge.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The test creates its schema dynamically through Flyway in Testcontainers.
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class KnowledgeGenerationUpgradeIntegrationTest {

    private static final String BASELINE_VERSION = "56";
    private static final String GENERATION_VERSION = "57";

    private static final UUID ZERO_GENERATION =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000000"
            );

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0057"
            );

    private static final UUID ACTOR_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbb0057"
            );

    private static final UUID USER_ROLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private JdbcTemplate jdbcTemplate;

    @Test
    void upgradesPopulatedV56AndPreservesLegacyZeroGenerationPerVersion() {
        try (
                PostgreSQLContainer postgres =
                        new PostgreSQLContainer(
                                "pgvector/pgvector:pg16"
                        )
        ) {
            postgres.start();

            DriverManagerDataSource dataSource =
                    new DriverManagerDataSource(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword()
                    );

            jdbcTemplate =
                    new JdbcTemplate(
                            dataSource
                    );

            migrateBaseline(
                    dataSource
            );

            seedPopulatedV56(
                    dataSource
            );

            List<Map<String, Object>> chunksBeforeUpgrade =
                    readLegacyChunkSnapshot();

            /*
             * This test is specifically about the V56 -> V57 contract.
             * Target V57 explicitly so unrelated future migrations cannot
             * change the expected migrationsExecuted count.
             */
            Flyway v57Upgrade =
                    flyway(
                            dataSource,
                            GENERATION_VERSION
                    );

            assertThat(
                    v57Upgrade
                            .migrate()
                            .migrationsExecuted
            ).isEqualTo(
                    1
            );

            v57Upgrade.validate();

            assertGenerationMigrationApplied();

            assertV57Backfill(
                    chunksBeforeUpgrade
            );

            List<Map<String, Object>> generationsAfterV57 =
                    readGenerationSnapshot();

            /*
             * Then migrate all remaining migrations as an additional
             * compatibility check. V58, V59, ... must not corrupt the V57
             * Knowledge backfill. Do not assert their count: this test must
             * remain stable when unrelated migrations are added later.
             */
            Flyway latest =
                    flyway(
                            dataSource
                    );

            latest.migrate();
            latest.validate();

            assertV57Backfill(
                    chunksBeforeUpgrade
            );

            assertThat(
                    readGenerationSnapshot()
            ).isEqualTo(
                    generationsAfterV57
            );

            assertGenerationMigrationApplied();
        }
    }

    private void migrateBaseline(
            DataSource dataSource
    ) {
        Flyway migration =
                flyway(
                        dataSource,
                        BASELINE_VERSION
                );

        migration.migrate();
        migration.validate();
    }

    private Flyway flyway(
            DataSource dataSource,
            String targetVersion
    ) {
        return Flyway.configure()
                .configuration(
                        Map.of(
                                "flyway.postgresql.transactional.lock",
                                "false"
                        )
                )
                .dataSource(
                        dataSource
                )
                .target(
                        targetVersion
                )
                .load();
    }

    private Flyway flyway(
            DataSource dataSource
    ) {
        return Flyway.configure()
                .configuration(
                        Map.of(
                                "flyway.postgresql.transactional.lock",
                                "false"
                        )
                )
                .dataSource(
                        dataSource
                )
                .load();
    }

    private void seedPopulatedV56(
            DataSource dataSource
    ) {
        jdbcTemplate.update(
                """
                insert into organizations (
                    id,
                    name,
                    normalized_name,
                    enabled,
                    version
                ) values (?, 'Upgrade', 'upgrade', true, 0)
                """,
                PLATFORM_ORGANIZATION_ID
        );

        TransactionTemplate transaction =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                dataSource
                        )
                );

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    insert into users (
                        id,
                        organization_id,
                        email,
                        password_hash,
                        full_name,
                        enabled,
                        token_version,
                        version
                    ) values (
                        ?,
                        ?,
                        'upgrade@test.local',
                        'encoded-password',
                        'Upgrade User',
                        true,
                        0,
                        0
                    )
                    """,
                    ACTOR_ID,
                    PLATFORM_ORGANIZATION_ID
            );

            jdbcTemplate.update(
                    """
                    insert into user_roles (
                        user_id,
                        role_id
                    ) values (?, ?)
                    """,
                    ACTOR_ID,
                    USER_ROLE_ID
            );
        });

        for (int index = 0; index < 2; index++) {
            UUID knowledgeBaseId =
                    UUID.randomUUID();

            UUID documentId =
                    UUID.randomUUID();

            UUID versionId =
                    UUID.randomUUID();

            insertReadyGraph(
                    knowledgeBaseId,
                    documentId,
                    versionId
            );

            insertChunk(
                    knowledgeBaseId,
                    documentId,
                    versionId,
                    0,
                    "Legacy evidence one"
            );

            insertChunk(
                    knowledgeBaseId,
                    documentId,
                    versionId,
                    1,
                    "Legacy evidence two"
            );
        }
    }

    private void assertV57Backfill(
            List<Map<String, Object>> expectedChunks
    ) {
        assertThat(
                readLegacyChunkSnapshot()
        ).isEqualTo(
                expectedChunks
        );

        Integer totalGenerations =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from knowledge_index_generations
                        """,
                        Integer.class
                );

        assertThat(
                totalGenerations
        ).isEqualTo(
                2
        );

        Integer activeEstimatedGenerations =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from knowledge_index_generations
                        where state = 'ACTIVE'
                          and publication_time_estimated
                          and chunk_count = 2
                        """,
                        Integer.class
                );

        assertThat(
                activeEstimatedGenerations
        ).isEqualTo(
                2
        );

        Integer distinctVersions =
                jdbcTemplate.queryForObject(
                        """
                        select count(
                            distinct document_version_id
                        )
                        from knowledge_index_generations
                        """,
                        Integer.class
                );

        assertThat(
                distinctVersions
        ).isEqualTo(
                2
        );

        Integer zeroGenerationRows =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from knowledge_index_generations
                        where index_generation = ?
                        """,
                        Integer.class,
                        ZERO_GENERATION
                );

        assertThat(
                zeroGenerationRows
        ).isEqualTo(
                2
        );

        Integer distinctGenerations =
                jdbcTemplate.queryForObject(
                        """
                        select count(
                            distinct index_generation
                        )
                        from knowledge_index_generations
                        """,
                        Integer.class
                );

        assertThat(
                distinctGenerations
        ).isEqualTo(
                1
        );

        Integer nonActiveGenerations =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from knowledge_index_generations
                        where state <> 'ACTIVE'
                        """,
                        Integer.class
                );

        assertThat(
                nonActiveGenerations
        ).isZero();
    }

    private void assertGenerationMigrationApplied() {
        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from flyway_schema_history
                        where version = ?
                          and success
                        """,
                        Integer.class,
                        GENERATION_VERSION
                );

        assertThat(
                count
        ).isEqualTo(
                1
        );
    }

    private List<Map<String, Object>> readLegacyChunkSnapshot() {
        return jdbcTemplate.queryForList(
                """
                select
                    id,
                    content,
                    content_sha256,
                    index_generation
                from knowledge_document_chunks
                order by id
                """
        );
    }

    private List<Map<String, Object>> readGenerationSnapshot() {
        return jdbcTemplate.queryForList(
                """
                select
                    document_version_id,
                    index_generation,
                    document_id,
                    knowledge_base_id,
                    organization_id,
                    extractor_version,
                    chunker_version,
                    embedding_model,
                    chunk_count,
                    publication_time_estimated,
                    state
                from knowledge_index_generations
                order by
                    document_version_id,
                    index_generation
                """
        );
    }

    private void insertReadyGraph(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId
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
                    'MEMBERS',
                    true,
                    ?,
                    0
                )
                """,
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                "Retrieval " + knowledgeBaseId,
                ACTOR_ID
        );

        jdbcTemplate.update(
                """
                insert into knowledge_base_memberships (
                    id,
                    knowledge_base_id,
                    organization_id,
                    user_id,
                    access_level,
                    version
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    'VIEWER',
                    0
                )
                """,
                UUID.randomUUID(),
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                ACTOR_ID
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
                    'hr-policy.txt',
                    true,
                    ?,
                    0
                )
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                ACTOR_ID
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
                    'hr-policy.txt',
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
                "retrieval-test/" + versionId,
                ACTOR_ID
        );

        jdbcTemplate.update(
                """
                update knowledge_documents
                set current_version_id = ?
                where id = ?
                """,
                versionId,
                documentId
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
                    started_at,
                    finished_at,
                    extractor_version,
                    chunker_version,
                    embedding_model,
                    extracted_char_count,
                    chunk_count,
                    version
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'READY',
                    1,
                    now(),
                    now(),
                    'test-extractor',
                    'test-chunker',
                    ?,
                    100,
                    2,
                    0
                )
                """,
                UUID.randomUUID(),
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId,
                "test-model"
        );
    }

    private void insertChunk(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            int ordinal,
            String content
    ) {
        jdbcTemplate.update(
                """
                insert into knowledge_document_chunks (
                    id,
                    organization_id,
                    knowledge_base_id,
                    document_id,
                    document_version_id,
                    ordinal,
                    content,
                    content_sha256,
                    estimated_tokens,
                    extractor_version,
                    chunker_version,
                    embedding_model,
                    embedding
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'test-extractor',
                    'test-chunker',
                    ?,
                    ?::vector
                )
                """,
                UUID.randomUUID(),
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId,
                ordinal,
                content,
                sha256(content),
                Math.max(
                        1,
                        content.length() / 4
                ),
                "test-model",
                "[1," + "0,".repeat(382) + "0]"
        );
    }

    private static String sha256(
            String value
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    value.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            )
                    );
        } catch (
                NoSuchAlgorithmException exception
        ) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is unavailable",
                    exception
            );
        }
    }
}
