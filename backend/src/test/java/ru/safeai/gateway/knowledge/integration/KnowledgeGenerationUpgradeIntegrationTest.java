package ru.safeai.gateway.knowledge.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The test creates its schema dynamically through Flyway in Testcontainers.
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class KnowledgeGenerationUpgradeIntegrationTest {
    private JdbcTemplate jdbcTemplate;
    private static final UUID PLATFORM_ORGANIZATION_ID=UUID.randomUUID();
    @Test void upgradesPopulatedV56AndPreservesLegacyZeroGenerationPerVersion() {
        try (var postgres=new PostgreSQLContainer("pgvector/pgvector:pg16")) {
            postgres.start();
            var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            jdbcTemplate=new JdbcTemplate(ds);
            Flyway.configure().configuration(java.util.Map.of("flyway.postgresql.transactional.lock","false")).dataSource(ds).target("56").load().migrate();
            UUID actor=UUID.randomUUID();
            jdbcTemplate.update("insert into organizations(id,name,normalized_name,enabled,version) values(?,'Upgrade','upgrade',true,0)",PLATFORM_ORGANIZATION_ID);
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            tx.executeWithoutResult(status -> {
            jdbcTemplate.update("insert into users(id,organization_id,email,password_hash,full_name,enabled,token_version,version) values(?,?,'upgrade@test.local','encoded-password','Upgrade User',true,0,0)",actor,PLATFORM_ORGANIZATION_ID);
            jdbcTemplate.update("insert into user_roles(user_id,role_id) values(?,?)",actor,UUID.fromString("11111111-1111-1111-1111-111111111111"));
            });
            for(int i=0;i<2;i++) {
                UUID kb=UUID.randomUUID(),doc=UUID.randomUUID(),version=UUID.randomUUID();
                insertReadyGraph(kb,doc,version,actor);
                insertChunk(kb,doc,version,0,"Legacy evidence one");
                insertChunk(kb,doc,version,1,"Legacy evidence two");
            }
            var before=jdbcTemplate.queryForList("select id,content,content_sha256,index_generation from knowledge_document_chunks order by id");
            var upgrade=Flyway.configure().configuration(java.util.Map.of("flyway.postgresql.transactional.lock","false")).dataSource(ds).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();
            assertThat(jdbcTemplate.queryForList("select id,content,content_sha256,index_generation from knowledge_document_chunks order by id")).isEqualTo(before);
            assertThat(jdbcTemplate.queryForObject("select count(*) from knowledge_index_generations where state='ACTIVE' and publication_time_estimated and chunk_count=2",Integer.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("select count(distinct index_generation) from knowledge_index_generations",Integer.class)).isEqualTo(1);
        }
    }
    private void insertReadyGraph(
            UUID knowledgeBaseId,
            UUID documentId,
            UUID versionId,
            UUID memberId
    ) {
        jdbcTemplate.update("""
                insert into knowledge_bases (
                    id, organization_id, name, visibility, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'MEMBERS', true, ?, 0)
                """,
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                "Retrieval " + knowledgeBaseId,
                memberId
        );
        jdbcTemplate.update("""
                insert into knowledge_base_memberships (
                    id, knowledge_base_id, organization_id, user_id,
                    access_level, version
                ) values (?, ?, ?, ?, 'VIEWER', 0)
                """,
                UUID.randomUUID(),
                knowledgeBaseId,
                PLATFORM_ORGANIZATION_ID,
                memberId
        );
        jdbcTemplate.update("""
                insert into knowledge_documents (
                    id, organization_id, knowledge_base_id, name, enabled,
                    created_by_user_id, version
                ) values (?, ?, ?, 'hr-policy.txt', true, ?, 0)
                """,
                documentId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                memberId
        );
        jdbcTemplate.update("""
                insert into knowledge_document_versions (
                    id, organization_id, knowledge_base_id, document_id,
                    version_number, original_filename, media_type, size_bytes,
                    sha256, storage_key, created_by_user_id
                ) values (?, ?, ?, ?, 1, 'hr-policy.txt', 'text/plain', 1,
                          ?, ?, ?)
                """,
                versionId,
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                "0".repeat(64),
                "retrieval-test/" + versionId,
                memberId
        );
        jdbcTemplate.update(
                "update knowledge_documents set current_version_id = ? "
                        + "where id = ?",
                versionId,
                documentId
        );
        jdbcTemplate.update("""
                insert into knowledge_ingestion_jobs (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, status, attempt, started_at,
                    finished_at, extractor_version, chunker_version,
                    embedding_model, extracted_char_count, chunk_count,
                    version
                ) values (?, ?, ?, ?, ?, 'READY', 1, now(), now(),
                          'test-extractor', 'test-chunker', ?, 100, 2, 0)
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
        jdbcTemplate.update("""
                insert into knowledge_document_chunks (
                    id, organization_id, knowledge_base_id, document_id,
                    document_version_id, ordinal, content, content_sha256,
                    estimated_tokens, extractor_version, chunker_version,
                    embedding_model, embedding
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test-extractor',
                          'test-chunker', ?, ?::vector)
                """,
                UUID.randomUUID(),
                PLATFORM_ORGANIZATION_ID,
                knowledgeBaseId,
                documentId,
                versionId,
                ordinal,
                content,
                sha256(content),
                Math.max(1, content.length() / 4),
                "test-model",
                "[1," + "0,".repeat(382) + "0]"
        );
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    )
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
