package ru.safeai.gateway.common.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseMigrationMainTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/safeai_contract_upgrade";
    private static final String PASSWORD = "unit-test-password";

    @TempDir
    Path directory;

    @Test
    void refusesUnreviewedMigrationCommands() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseMigrationMain.main(new String[]{"clean"}));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseMigrationMain.main(new String[]{"repair"}));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseMigrationMain.main(new String[]{"ci-v85", "extra"}));
    }

    @Test
    void rejectsMissingUrlAndBlankPasswordFilePath() throws Exception {
        Map<String, String> env = environment();
        env.remove("SAFEAI_MIGRATION_JDBC_URL");
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> DatabaseMigrationMain.run(new String[0], env::get));
        assertEquals("Missing SAFEAI_MIGRATION_JDBC_URL", missing.getMessage());
        env.put("SAFEAI_MIGRATION_JDBC_URL", URL);
        env.put("SAFEAI_MIGRATION_PASSWORD_FILE", " ");
        IllegalStateException blank = assertThrows(IllegalStateException.class,
                () -> DatabaseMigrationMain.run(new String[0], env::get));
        assertEquals("Missing SAFEAI_MIGRATION_PASSWORD_FILE", blank.getMessage());
    }

    @Test
    void refusesEmptyMigrationPasswordBeforeConnecting() throws Exception {
        Map<String, String> env = environment();
        Files.writeString(Path.of(env.get("SAFEAI_MIGRATION_PASSWORD_FILE")), " \n");
        try (MockedStatic<DriverManager> jdbc = mockStatic(DriverManager.class)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> DatabaseMigrationMain.run(new String[0], env::get));
            assertEquals("Empty migration password", failure.getMessage());
            jdbc.verifyNoInteractions();
        }
    }

    @Test
    void rejectsMissingOrUnsafeMigrationIdentityBeforeFlyway() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.identity.next()).thenReturn(false);
        fixture.assertBlockedBeforeMigration(new String[0]);
        when(fixture.identity.next()).thenReturn(true);
        when(fixture.identity.getBoolean(1)).thenReturn(false);
        fixture.assertBlockedBeforeMigration(new String[0]);
    }

    @Test
    void restrictsCiV85ToTheDedicatedUpgradeDatabase() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.identity.getString(2)).thenReturn("safeai_production");
        fixture.assertBlockedBeforeMigration(new String[]{"ci-v85"});
    }

    @Test
    void ciV85MigratesOnlyTo85WithoutApplyingRuntimeAcl() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run("ci-v85");
        verify(fixture.configuration).target("85");
        verify(fixture.configuration).ignoreMigrationPatterns("versioned:pending");
        verify(fixture.extension).setTransactionalLock(false);
        verify(fixture.flyway).migrate();
        verify(fixture.flyway, never()).validate();
        verifyNoInteractions(fixture.aclConnection, fixture.aclStatement);
    }

    @Test
    void normalMigrationValidatesAndCommitsReviewedAclOnlyAfterHistoryCheck() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run();
        verify(fixture.configuration).dataSource(URL, "safeai_migrator", PASSWORD);
        verify(fixture.configuration).cleanDisabled(true);
        verify(fixture.configuration).baselineOnMigrate(false);
        verify(fixture.configuration).outOfOrder(false);
        verify(fixture.configuration).validateOnMigrate(true);
        verify(fixture.configuration, never()).target(anyString());
        var order = inOrder(fixture.flyway, fixture.aclConnection, fixture.aclStatement, fixture.history);
        order.verify(fixture.flyway).info();
        order.verify(fixture.flyway).migrate();
        order.verify(fixture.flyway).validate();
        order.verify(fixture.aclConnection).setAutoCommit(false);
        order.verify(fixture.aclStatement).execute("SET LOCAL lock_timeout = '5s'; SET LOCAL statement_timeout = '120s'");
        order.verify(fixture.aclStatement).executeQuery(anyString());
        order.verify(fixture.history).next();
        order.verify(fixture.history).getBoolean(1);
        order.verify(fixture.aclStatement).execute(aclPolicy());
        order.verify(fixture.aclConnection).commit();
        verify(fixture.aclConnection, never()).rollback();
    }

    @Test
    void newMigrationCannotRunUntilExplicitlyReviewed() throws Exception {
        Fixture fixture = new Fixture();
        MigrationInfo[] expanded = Arrays.copyOf(fixture.approved, fixture.approved.length + 1);
        expanded[expanded.length - 1] = migration(95, "V95__unreviewed.sql");
        when(fixture.info.all()).thenReturn(expanded);
        assertThrows(IllegalStateException.class, fixture::run);
        verify(fixture.flyway, never()).migrate();
        verifyNoInteractions(fixture.aclConnection, fixture.aclStatement);
    }

    @Test
    void missingDuplicateRenamedOrUnresolvedMigrationIsBlockedBeforeDdl() throws Exception {
        Fixture fixture = new Fixture();
        var reviewed = RuntimeAclPolicy.verifyReviewedResources();
        assertThrows(IllegalStateException.class, () -> RuntimeAclPolicy.verifyResolvedMigrations(
                Arrays.copyOf(fixture.approved, fixture.approved.length - 1), reviewed));
        var changed = fixture.approved.clone();
        changed[0] = changed[1];
        assertThrows(IllegalStateException.class, () -> RuntimeAclPolicy.verifyResolvedMigrations(changed, reviewed));
        changed[0] = migration(1, "V1__renamed.sql");
        assertThrows(IllegalStateException.class, () -> RuntimeAclPolicy.verifyResolvedMigrations(changed, reviewed));
        changed[0] = fixture.approved[0];
        when(changed[0].getState()).thenReturn(MigrationState.FAILED);
        assertThrows(IllegalStateException.class, () -> RuntimeAclPolicy.verifyResolvedMigrations(changed, reviewed));
        when(changed[0].getState()).thenReturn(MigrationState.SUCCESS);
        RuntimeAclPolicy.verifyResolvedMigrations(changed, reviewed);
        when(changed[0].getVersion()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> RuntimeAclPolicy.verifyResolvedMigrations(changed, reviewed));
    }

    @Test
    void reviewedResourcesAndHistoryCoverExactlyCurrentMigrations() throws Exception {
        assertEquals(94, RuntimeAclPolicy.verifyReviewedResources().size());
        assertEquals("V92__organization_model_policy_budget_finite_values.sql",
                RuntimeAclPolicy.verifyReviewedResources().get(92));
        assertEquals("V93__model_policy_preview_confirmations.sql", RuntimeAclPolicy.verifyReviewedResources().get(93));
        assertEquals("V94__operation_correlation.sql", RuntimeAclPolicy.verifyReviewedResources().get(94));
        assertEquals(true, RuntimeAclPolicy.historyQuery().contains("BETWEEN 1 AND 94"));
    }

    @Test
    void v94TransactionConfigurationCannotBeRemovedOrChanged() throws Exception {
        String config = "/db/migration/V94__operation_correlation.sql.conf";
        assertEquals("executeInTransaction=true", new String(readResource(config), StandardCharsets.UTF_8).strip());
        assertThrows(IllegalStateException.class, () -> RuntimeAclPolicy.verifyReviewedResources(
                path -> config.equals(path) ? null : readResource(path)));
        assertThrows(IllegalStateException.class, () -> RuntimeAclPolicy.verifyReviewedResources(
                path -> config.equals(path) ? "executeInTransaction=false\n".getBytes(StandardCharsets.UTF_8)
                        : readResource(path)));
        assertEquals(94, RuntimeAclPolicy.verifyReviewedResources().size());
    }

    private static byte[] readResource(String resource) throws java.io.IOException {
        try (var input = DatabaseMigrationMain.class.getResourceAsStream(resource)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static MigrationInfo migration(int version, String script) {
        var migration = mock(MigrationInfo.class);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion(Integer.toString(version)));
        when(migration.getScript()).thenReturn(script);
        when(migration.getState()).thenReturn(MigrationState.PENDING);
        return migration;
    }

    @Test
    void rollsBackAndRefusesAclWhenMigrationHistoryIsMissingOrInvalid() throws Exception {
        Fixture missing = new Fixture();
        when(missing.history.next()).thenReturn(false);
        missing.assertInvalidHistory();
        Fixture invalid = new Fixture();
        when(invalid.history.getBoolean(1)).thenReturn(false);
        invalid.assertInvalidHistory();
    }

    @Test
    void rollsBackAndPropagatesSqlFailureWhileApplyingAcl() throws Exception {
        Fixture fixture = new Fixture();
        SQLException sqlFailure = new SQLException("ACL execution failed");
        doThrow(sqlFailure).when(fixture.aclStatement).execute(aclPolicy());
        SQLException failure = assertThrows(SQLException.class, fixture::run);
        assertSame(sqlFailure, failure);
        verify(fixture.aclConnection).rollback();
        verify(fixture.aclConnection, never()).commit();
    }

    private Map<String, String> environment() throws Exception {
        Path password = Files.writeString(directory.resolve("migration-password"), PASSWORD + "\n");
        return new HashMap<>(Map.of(
                "SAFEAI_MIGRATION_JDBC_URL", URL,
                "SAFEAI_MIGRATION_PASSWORD_FILE", password.toString()));
    }

    private static String aclPolicy() throws Exception {
        try (var input = DatabaseMigrationMain.class.getResourceAsStream("/db/acl/runtime-v90.sql")) {
            return new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private final class Fixture {
        private final Connection identityConnection = mock(Connection.class);
        private final Statement identityStatement = mock(Statement.class);
        private final ResultSet identity = mock(ResultSet.class);
        private final Connection aclConnection = mock(Connection.class);
        private final Statement aclStatement = mock(Statement.class);
        private final ResultSet history = mock(ResultSet.class);
        private final FluentConfiguration configuration = mock(FluentConfiguration.class, RETURNS_SELF);
        private final Flyway flyway = mock(Flyway.class);
        private final MigrationInfoService info = mock(MigrationInfoService.class);
        private final MigrationInfo[] approved;
        private final PostgreSQLConfigurationExtension extension = mock(PostgreSQLConfigurationExtension.class);

        private Fixture() throws Exception {
            when(identityConnection.createStatement()).thenReturn(identityStatement);
            when(identityStatement.executeQuery(anyString())).thenReturn(identity);
            when(identity.next()).thenReturn(true);
            when(identity.getBoolean(1)).thenReturn(true);
            when(identity.getString(2)).thenReturn("safeai_contract_upgrade");
            when(aclConnection.createStatement()).thenReturn(aclStatement);
            when(aclStatement.executeQuery(anyString())).thenReturn(history);
            when(history.next()).thenReturn(true);
            when(history.getBoolean(1)).thenReturn(true);
            when(configuration.load()).thenReturn(flyway);
            when(flyway.getConfigurationExtension(PostgreSQLConfigurationExtension.class)).thenReturn(extension);
            approved = RuntimeAclPolicy.verifyReviewedResources().entrySet().stream()
                    .map(entry -> migration(entry.getKey(), entry.getValue())).toArray(MigrationInfo[]::new);
            when(flyway.info()).thenReturn(info);
            when(info.all()).thenReturn(approved);
        }

        private void run(String... args) throws Exception {
            Map<String, String> env = environment();
            try (MockedStatic<DriverManager> jdbc = mockStatic(DriverManager.class);
                 MockedStatic<Flyway> flywayFactory = mockStatic(Flyway.class)) {
                jdbc.when(() -> DriverManager.getConnection(URL, "safeai_migrator", PASSWORD))
                        .thenReturn(identityConnection, aclConnection);
                flywayFactory.when(Flyway::configure).thenReturn(configuration);
                DatabaseMigrationMain.run(args, env::get);
            }
        }

        private void assertBlockedBeforeMigration(String[] args) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> run(args));
            assertEquals("Migration identity/database check failed", failure.getMessage());
            verifyNoInteractions(configuration, flyway, aclConnection, aclStatement);
        }

        private void assertInvalidHistory() throws Exception {
            IllegalStateException failure = assertThrows(IllegalStateException.class, this::run);
            assertEquals("ACL policy requires exactly V1-V94", failure.getMessage());
            verify(aclStatement, never()).execute(aclPolicy());
            verify(aclConnection).rollback();
            verify(aclConnection, never()).commit();
        }
    }
}
