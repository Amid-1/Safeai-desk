package ru.safeai.gateway.common.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.function.Function;

/** Одноразовая команда миграции, без запуска Spring, HTTP и фоновых задач. */
public final class DatabaseMigrationMain {

    private DatabaseMigrationMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args, System::getenv);
    }

    static void run(String[] args, Function<String, String> environment) throws Exception {
        if (args.length != 0 && !(args.length == 1 && "ci-v85".equals(args[0]))) {
            throw new IllegalArgumentException("Unexpected migration arguments");
        }
        String url = required(environment, "SAFEAI_MIGRATION_JDBC_URL");
        String password = Files.readString(Path.of(required(environment, "SAFEAI_MIGRATION_PASSWORD_FILE"))).strip();
        if (password.isEmpty()) {
            throw new IllegalStateException("Empty migration password");
        }
        boolean ciV85 = args.length == 1;
        try (var connection = DriverManager.getConnection(url, "safeai_migrator", password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT session_user='safeai_migrator' AND current_user=session_user
                            AND NOT (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication)
                            AND current_setting('server_version_num')::int / 10000 = 16
                            AND (NOT has_schema_privilege('safeai_app','public','CREATE'))
                            AND NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.member=pg_roles.oid),
                            current_database()
                     FROM pg_roles WHERE rolname=current_user
                     """)) {
            if (!result.next() || !result.getBoolean(1)
                    || (ciV85 && !"safeai_contract_upgrade".equals(result.getString(2)))) {
                throw new IllegalStateException("Migration identity/database check failed");
            }
        }
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, "safeai_migrator", password)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .createSchemas(false).baselineOnMigrate(false).outOfOrder(false)
                .cleanDisabled(true).validateMigrationNaming(true).validateOnMigrate(true)
                .ignoreMigrationPatterns(new String[0])
                .group(false).mixed(false);
        if (ciV85) {
            configuration.target("85");
            configuration.ignoreMigrationPatterns("versioned:pending");
        }
        Flyway flyway = configuration.load();
        flyway.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        flyway.migrate();
        if (ciV85) {
            return;
        }
        flyway.validate();
        try (var connection = DriverManager.getConnection(url, "safeai_migrator", password);
             InputStream input = DatabaseMigrationMain.class.getResourceAsStream("/db/acl/runtime-v90.sql")) {
            if (input == null) {
                throw new IllegalStateException("Missing runtime ACL policy");
            }
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '5s'; SET LOCAL statement_timeout = '120s'");
                try (var result = statement.executeQuery("""
                        SELECT count(*)=90 AND count(DISTINCT version)=90
                            AND bool_and(success AND version ~ '^[0-9]+$' AND version::int BETWEEN 1 AND 90)
                        FROM public.flyway_schema_history WHERE version IS NOT NULL
                        """)) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new IllegalStateException("ACL policy requires exactly V1-V90");
                    }
                }
                statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String required(Function<String, String> environment, String name) {
        String value = environment.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + name);
        }
        return value;
    }
}
