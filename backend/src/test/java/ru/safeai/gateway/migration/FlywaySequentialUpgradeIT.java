package ru.safeai.gateway.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies every Flyway migration separately against one real PostgreSQL database.
 *
 * <p>V0 is an intentional test-only role bootstrap. It must run before V1,
 * not be hidden or excluded to make the migration count look convenient.
 * Production migrations begin at V1 and currently extend through V91.</p>
 */
@Tag("integration")
@Testcontainers
class FlywaySequentialUpgradeIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Test
    void everyVersionCanBeAppliedInOrderWithoutDroppingSchema() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .as("Flyway must target its actual isolated Testcontainers database")
                .isEqualTo(POSTGRES.getDatabaseName());

        Long publicSchemaOid = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT oid::bigint FROM pg_catalog.pg_namespace
                WHERE nspname = 'public'
                """, Long.class), "public schema must exist before migration");

        Flyway catalog = flyway(dataSource, null);
        List<String> versions = Arrays.stream(catalog.info().all())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .map(MigrationVersion::getVersion)
                .toList();

        // Validate the complete catalog, not just the migration that failed.
        assertThat(versions).as("Expected test V0 and production versions through V91")
                .hasSizeGreaterThanOrEqualTo(92)
                .startsWith("0", "1")
                .contains("91");

        for (int index = 0; index < versions.size(); index++) {
            String version = versions.get(index);
            assertThat(version)
                    .as("Migration versions must be contiguous and ordered: V%s", index)
                    .isEqualTo(Integer.toString(index));

            Flyway step = flyway(dataSource, MigrationVersion.fromVersion(version));
            var result = step.migrate();

            // V0 and V1 are TWO distinct migrations. Later targets add ONE each.
            assertThat(result.migrationsExecuted)
                    .as("Exactly one migration must be applied at target V%s", version)
                    .isEqualTo(1);

            List<String> recorded = jdbc.queryForList("""
                    SELECT version FROM public.flyway_schema_history
                    WHERE success = true AND version IS NOT NULL
                    ORDER BY installed_rank
                    """, String.class);
            assertThat(recorded)
                    .as("Applied Flyway history must be an exact prefix through V%s", version)
                    .containsExactlyElementsOf(versions.subList(0, index + 1));

            assertThat(step.info().current())
                    .as("Flyway must report current version after V%s", version)
                    .isNotNull();
            assertThat(step.info().current().getVersion().getVersion())
                    .isEqualTo(version);

            assertThat(jdbc.queryForObject("""
                    SELECT oid::bigint FROM pg_catalog.pg_namespace
                    WHERE nspname = 'public'
                    """, Long.class))
                    .as("Migration V%s must not drop and recreate the public schema", version)
                    .isEqualTo(publicSchemaOid);

            if (index == 0) {
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_catalog.pg_roles
                        WHERE rolname = 'safeai_chat_erasure_worker'
                          AND rolcanlogin AND NOT rolsuper
                        """, Integer.class))
                        .as("Test V0 must bootstrap an isolated non-superuser login")
                        .isEqualTo(1);
            }

            if (index == 21) {
                // V21 uses CREATE INDEX CONCURRENTLY. Verify the index exists
                // AND is ready and valid, not merely recorded as migrated.
                assertThat(jdbc.queryForObject("""
                        SELECT i.indisvalid AND i.indisready
                        FROM pg_catalog.pg_index i
                        JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
                        JOIN pg_catalog.pg_namespace ns ON ns.oid = idx.relnamespace
                        WHERE ns.nspname = 'public'
                          AND idx.relname = 'idx_refresh_tokens_cleanup_batch'
                        """, Boolean.class))
                        .as("V21 concurrent cleanup index must be ready and valid")
                        .isTrue();
            }
        }

        Flyway finalFlyway = flyway(dataSource, null);
        finalFlyway.validate();
        assertThat(finalFlyway.migrate().migrationsExecuted)
                .as("A second full migration must be an idempotent no-op")
                .isZero();

        List<String> finalHistory = jdbc.queryForList("""
                SELECT version FROM public.flyway_schema_history
                WHERE success = true AND version IS NOT NULL
                ORDER BY installed_rank
                """, String.class);
        assertThat(finalHistory)
                .as("Re-running Flyway must not change the migration ledger")
                .containsExactlyElementsOf(versions);
    }

    private static Flyway flyway(
            DriverManagerDataSource dataSource,
            MigrationVersion target
    ) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .validateOnMigrate(true);
        // V21 executes CREATE/DROP INDEX CONCURRENTLY outside transactions.
        // PostgreSQL transactional advisory locks can deadlock with this DDL.
        // Session-level advisory locks still serialize Flyway migrations.
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);
        if (target != null) {
            configuration.target(target);
        }
        Flyway configured = configuration.load();
        assertThat(configured.getConfigurationExtension(
                PostgreSQLConfigurationExtension.class).isTransactionalLock())
                .as("Flyway must use session advisory locks for concurrent indexes")
                .isFalse();
        return configured;
    }
}
