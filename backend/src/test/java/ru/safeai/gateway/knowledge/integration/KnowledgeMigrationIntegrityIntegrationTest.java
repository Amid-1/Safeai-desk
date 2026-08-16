package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeMigrationIntegrityIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final String NOT_NULL = "NO";

    @Autowired
    private JdbcTemplate knowledgeJdbcTemplate;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void requiredKnowledgeTablesExist() {
        List<String> tables =
                knowledgeJdbcTemplate.queryForList(
                        """
                        select table_name
                        from information_schema.tables
                        where table_schema = 'public'
                          and table_name in (
                              'knowledge_bases',
                              'knowledge_base_memberships',
                              'knowledge_documents',
                              'knowledge_document_versions',
                              'knowledge_ingestion_jobs'
                          )
                        order by table_name
                        """,
                        String.class
                );

        assertThat(tables)
                .containsExactlyInAnyOrder(
                        "knowledge_bases",
                        "knowledge_base_memberships",
                        "knowledge_documents",
                        "knowledge_document_versions",
                        "knowledge_ingestion_jobs"
                );
    }

    @Test
    void documentVersionIntegrityColumnsHaveExpectedShape() {
        List<Map<String, Object>> rows =
                knowledgeJdbcTemplate.queryForList(
                        """
                        select
                            column_name,
                            is_nullable,
                            character_maximum_length
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'knowledge_document_versions'
                          and column_name in (
                              'original_filename',
                              'media_type',
                              'sha256',
                              'storage_key'
                          )
                        """
                );

        assertThat(rows).hasSize(4);

        assertRequiredVarcharColumn(
                rows,
                "original_filename",
                255
        );

        assertRequiredVarcharColumn(
                rows,
                "media_type",
                127
        );

        assertRequiredVarcharColumn(
                rows,
                "sha256",
                64
        );

        assertRequiredVarcharColumn(
                rows,
                "storage_key",
                1024
        );
    }

    @Test
    void knowledgeBaseNameAndMembershipUniqueInvariantsExist() {
        Integer nameIndex =
                knowledgeJdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from pg_indexes
                        where schemaname = 'public'
                          and indexname = 'ux_knowledge_bases_org_name_lower'
                        """,
                        Integer.class
                );

        Integer membershipConstraint =
                knowledgeJdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from pg_constraint
                        where conname = 'uq_knowledge_base_memberships_kb_user'
                        """,
                        Integer.class
                );

        assertThat(nameIndex).isEqualTo(1);
        assertThat(membershipConstraint).isEqualTo(1);
    }

    @Test
    void documentVersionImmutabilityTriggerIsInstalled() {
        Integer triggerCount =
                knowledgeJdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from pg_trigger trigger
                        join pg_class relation
                          on relation.oid = trigger.tgrelid
                        join pg_namespace namespace
                          on namespace.oid = relation.relnamespace
                        where namespace.nspname = 'public'
                          and relation.relname = 'knowledge_document_versions'
                          and not trigger.tgisinternal
                        """,
                        Integer.class
                );

        assertThat(triggerCount)
                .isNotNull()
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void criticalKnowledgeForeignKeysArePresent() {
        List<String> foreignKeyTables =
                knowledgeJdbcTemplate.queryForList(
                        """
                        select distinct relation.relname
                        from pg_constraint constraint_row
                        join pg_class relation
                          on relation.oid = constraint_row.conrelid
                        join pg_namespace namespace
                          on namespace.oid = relation.relnamespace
                        where constraint_row.contype = 'f'
                          and namespace.nspname = 'public'
                          and relation.relname in (
                              'knowledge_base_memberships',
                              'knowledge_documents',
                              'knowledge_document_versions',
                              'knowledge_ingestion_jobs'
                          )
                        """,
                        String.class
                );

        assertThat(foreignKeyTables)
                .contains(
                        "knowledge_base_memberships",
                        "knowledge_documents",
                        "knowledge_document_versions",
                        "knowledge_ingestion_jobs"
                );
    }

    private static void assertRequiredVarcharColumn(
            List<Map<String, Object>> rows,
            String columnName,
            int maxLength
    ) {
        Map<String, Object> row =
                findColumn(
                        rows,
                        columnName
                );

        assertThat(row.get("is_nullable"))
                .as("%s must be NOT NULL", columnName)
                .isEqualTo(NOT_NULL);

        assertThat(row.get("character_maximum_length"))
                .as("%s max length", columnName)
                .isInstanceOf(Number.class);

        assertThat(
                ((Number) row.get(
                        "character_maximum_length"
                )).intValue()
        )
                .as("%s max length", columnName)
                .isEqualTo(maxLength);
    }

    private static Map<String, Object> findColumn(
            List<Map<String, Object>> rows,
            String columnName
    ) {
        return rows.stream()
                .filter(
                        row ->
                                columnName.equals(
                                        row.get("column_name")
                                )
                )
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError(
                                "Column not found: "
                                        + columnName
                        )
                );
    }
}