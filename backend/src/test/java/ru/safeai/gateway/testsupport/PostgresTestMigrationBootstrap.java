// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/testsupport/PostgresTestMigrationBootstrap.java
package ru.safeai.gateway.testsupport;

import ru.safeai.gateway.common.db.DatabaseMigrationMain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.WeakHashMap;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Bootstrap only disposable PostgreSQL integration containers.
 * Preserve the root fixture connection, but run all versioned migrations as
 * the separate non-superuser schema owner required by V108. Never used in main.
 */
public final class PostgresTestMigrationBootstrap {
    public static final String MIGRATOR = "safeai_migrator";
    public static final String MIGRATOR_PASSWORD = "test-only-shared-migrator-password";
    private static final String TEST_V0 = "/db/migration/V0__test_chat_erasure_roles.sql";

    private PostgresTestMigrationBootstrap() {
    }

    private static final WeakHashMap<PostgreSQLContainer, String> SPRING_PREPARED = new WeakHashMap<>();

    // Retain the previously accepted shared fixture entry point unchanged in scope.
    static void prepare(String jdbcUrl, String login, String password) {
        if (!"safeai".equals(login)) throw new IllegalStateException("Unexpected shared fixture login");
        prepare(jdbcUrl, login, password, "safeai_test", false);
    }

    public static void prepare(PostgreSQLContainer container) {
        prepareContainer(container, false);
    }

    /** Sequential upgrade alone intentionally records V0 under the fixture root. */
    public static void prepareAfterRecordedV0(PostgreSQLContainer container) {
        prepareContainer(container, true);
    }

    private static void prepareContainer(PostgreSQLContainer container, boolean recordedV0) {
        if (!container.isRunning() || container.getContainerId() == null) {
            throw new IllegalStateException("Expected running disposable PostgreSQL container");
        }
        prepare(container.getJdbcUrl(), container.getUsername(), container.getPassword(),
                container.getDatabaseName(), recordedV0);
    }

    public static DriverManagerDataSource migrationDataSource(PostgreSQLContainer container) {
        return new DriverManagerDataSource(container.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD);
    }

    /** Explicit Flyway credentials, no ServiceConnection override of migration identity. */
    public static synchronized void configureSpring(DynamicPropertyRegistry registry, PostgreSQLContainer container) {
        if (!container.isRunning()) container.start();
        String id = container.getContainerId();
        if (!id.equals(SPRING_PREPARED.get(container))) {
            prepare(container);
            SPRING_PREPARED.put(container, id);
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.flyway.url", container::getJdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATOR);
        registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        registry.add("spring.flyway.locations", PostgresTestMigrationBootstrap::productionLocation);
        registry.add("spring.flyway.postgresql.transactional-lock", () -> "false");
    }

    private static void prepare(String jdbcUrl, String login, String password,
                                String database, boolean recordedV0) {
        try (var root = DriverManager.getConnection(jdbcUrl, login, password)) {
            try (var statement = root.prepareStatement("""
                    SELECT current_database()=? AND session_user=?
                        AND current_user=session_user
                        AND (SELECT rolsuper FROM pg_roles WHERE rolname=session_user)
                        AND (to_regclass('public.flyway_schema_history') IS NOT NULL)=?
                        AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='safeai_migrator')
                    """)) {
                statement.setString(1, database);
                statement.setString(2, login);
                statement.setBoolean(3, recordedV0);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean(1)) {
                        throw new IllegalStateException("Expected isolated disposable PostgreSQL bootstrap");
                    }
                }
            }
            if (recordedV0) {
                try (var statement = root.createStatement(); var rs = statement.executeQuery("""
                        SELECT count(*)=1 AND bool_and(success AND version='0'
                            AND script='V0__test_chat_erasure_roles.sql' AND installed_by=session_user)
                        FROM public.flyway_schema_history WHERE version IS NOT NULL
                        """)) {
                    if (!rs.next() || !rs.getBoolean(1)) {
                        throw new IllegalStateException("Sequential bootstrap requires exactly recorded V0");
                    }
                }
            }
            // V0 is an existing test-only role provisioning script. Execute it
            // as fixture bootstrap, not as part of the production Flyway history.
            String roles;
            try (var input = PostgresTestMigrationBootstrap.class.getResourceAsStream(TEST_V0)) {
                if (input == null) throw new IllegalStateException("Missing test role bootstrap V0");
                roles = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            root.setAutoCommit(false);
            try (var statement = root.createStatement()) {
                if (!recordedV0) statement.execute(roles);
                statement.execute("""
                        CREATE ROLE safeai_migrator LOGIN PASSWORD 'test-only-shared-migrator-password'
                            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
                        REVOKE CREATE ON SCHEMA public FROM PUBLIC;
                        ALTER SCHEMA public OWNER TO safeai_migrator;
                        CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
                        """);
                if (recordedV0) {
                    // Preserve the actual V0 history row; only transfer ownership.
                    statement.execute("ALTER TABLE public.flyway_schema_history OWNER TO safeai_migrator");
                }
                root.commit();
            } catch (SQLException | RuntimeException failure) {
                root.rollback();
                throw failure;
            }
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("PostgreSQL test migration bootstrap failed", failure);
        }
        // Verify actual LOGIN and ownership, rather than assuming role DDL worked.
        try (var migrator = DriverManager.getConnection(jdbcUrl, MIGRATOR, MIGRATOR_PASSWORD);
             var statement = migrator.createStatement(); var rs = statement.executeQuery("""
                    SELECT session_user='safeai_migrator' AND current_user=session_user
                      AND (SELECT NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
                           FROM pg_roles WHERE rolname=session_user)
                      AND (SELECT nspowner=session_user::regrole FROM pg_namespace WHERE nspname='public')
                      AND NOT pg_has_role('safeai_app',session_user,'MEMBER')
                      AND NOT has_schema_privilege('safeai_app','public','CREATE')
                    """)) {
            if (!rs.next() || !rs.getBoolean(1)) {
                throw new IllegalStateException("Test migration owner is not isolated");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot authenticate separate test migration owner", failure);
        }
    }

    /** Positive control after latest migration, also detects a root-owned guard. */
    public static void verifyMigratedSchema(JdbcTemplate jdbc, boolean recordedV0) {
        Boolean history = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE version='0' AND success)=?
                    AND count(DISTINCT version) FILTER (WHERE version IN (SELECT v::text FROM generate_series(1,108) v) AND success)=108
                    AND bool_and(success AND (version='0' OR installed_by='safeai_migrator'))
                FROM public.flyway_schema_history WHERE version IS NOT NULL
                """, Boolean.class, recordedV0 ? 1 : 0);
        Boolean owner = jdbc.queryForObject("""
                SELECT (SELECT count(*)=2 FROM pg_proc
                    WHERE oid IN ('public.guard_erasure_review_v105()'::regprocedure,
                                  'public.guard_erasure_hold_v105()'::regprocedure)
                    AND proowner='safeai_migrator'::regrole AND prosecdef)
                    AND (SELECT NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
                        FROM pg_roles WHERE rolname='safeai_migrator')
                    AND NOT has_table_privilege('safeai_app','public.erasure_review_authorities_v105','UPDATE')
                    AND NOT has_function_privilege('safeai_app','public.guard_erasure_hold_v105()','EXECUTE')
                    AND NOT has_function_privilege('safeai_app','public.guard_erasure_review_v105()','EXECUTE')
                """, Boolean.class);
        if (!Boolean.TRUE.equals(history) || !Boolean.TRUE.equals(owner)) {
            throw new AssertionError("Expected complete migration history and isolated V108 owner/ACL");
        }
    }

    /** Resolve the real main build output, not the test resource directory. */
    public static String productionLocation() {
        try {
            Path main = Path.of(DatabaseMigrationMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path migrations = main.resolve("db/migration").toRealPath();
            if (!Files.isDirectory(migrations)
                    || !Files.isRegularFile(migrations.resolve("V1__init_schema.sql"))
                    || !Files.isRegularFile(migrations.resolve("V108__erasure_review_guard_lock_privileges.sql"))
                    || Files.exists(migrations.resolve("V0__test_chat_erasure_roles.sql"))) {
                throw new IllegalStateException("Expected isolated production migration directory");
            }
            return "filesystem:" + migrations;
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("Cannot locate production migration resources", failure);
        }
    }
}
