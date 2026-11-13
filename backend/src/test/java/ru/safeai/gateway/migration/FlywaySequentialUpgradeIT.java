package ru.safeai.gateway.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Поэтапный путь V1 -> V2 -> ... -> Vn на одной PostgreSQL.
 * Это проверка последовательного применения к пустой исходной схеме;
 * она НЕ заменяет rehearsal со снимком production-данных.
 */
@Testcontainers
class FlywaySequentialUpgradeIT {
    private static final Pattern NAME = Pattern.compile("V([1-9][0-9]*)__.+\\.sql");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Test
    void everyVersionCanBeAppliedInOrderWithoutDroppingSchema() throws IOException {
        List<Integer> versions;
        try (Stream<Path> stream = Files.list(MIGRATIONS)) {
            versions = stream
                    .map(path -> NAME.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();
        }
        assertThat(versions).isNotEmpty();
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).as("Непрерывная история Flyway").isEqualTo(i + 1);
        }
        for (int version : versions) {
            Flyway flyway = newFlyway(version);
            assertThat(flyway.migrate().migrationsExecuted)
                    .as("Точно одна миграция V" + version)
                    .isEqualTo(1);
            assertThat(flyway.info().current()).isNotNull();
            assertThat(flyway.info().current().getVersion().getVersion())
                    .isEqualTo(Integer.toString(version));
        }
        Flyway all = newFlyway(null);
        assertThat(all.validateWithResult().validationSuccessful).isTrue();
        assertThat(all.migrate().migrationsExecuted).isZero();
    }


    private static Flyway newFlyway(Integer targetVersion) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true);

        // Миграции с CREATE INDEX CONCURRENTLY (V21 и другие) требуют
        // session-level advisory lock, а не transactional lock Flyway.
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);

        if (targetVersion != null) {
            configuration.target(MigrationVersion.fromVersion(targetVersion.toString()));
        }
        return configuration.load();
    }
}
