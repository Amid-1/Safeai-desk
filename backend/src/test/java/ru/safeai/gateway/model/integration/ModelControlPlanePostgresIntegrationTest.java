package ru.safeai.gateway.model.integration;

import ru.safeai.gateway.testsupport.PostgresTestMigrationBootstrap;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.domain.RubBudgetSnapshot;
import ru.safeai.gateway.model.dto.CreateModelCatalogVersionRequest;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;
import ru.safeai.gateway.model.repository.ModelPolicyPreviewConfirmationRepository;
import ru.safeai.gateway.model.service.ModelPolicyPreviewConfirmationService;
import ru.safeai.gateway.model.service.ModelPolicyPreviewService;
import ru.safeai.gateway.model.exception.ModelRouteDeniedException;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.service.ModelCatalogService;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.OrganizationModelPolicyService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * End-to-end PostgreSQL coverage for the append-only Model Control Plane.
 *
 * <p>This suite intentionally runs real Flyway migrations and real JDBC
 * repositories. Only the external runtime status and audit sink are mocked.
 * Routing itself uses the current production constructor, including the
 * DB-backed request mutex.</p>
 */
@Tag("integration")
@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelControlPlanePostgresIntegrationTest {

    private static final BigDecimal ZERO_MONEY =
            new BigDecimal("0.000000000000");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg16")
                    .withDatabaseName("safeai_model_test")
                    .withUsername("safeai")
                    .withPassword("safeai");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;

    private UUID organizationId;
    private UUID userId;
    private UUID chatId;

    private ModelCatalogRepository catalogRepository;
    private OrganizationModelPolicyRepository policyRepository;
    private ModelRouteDecisionRepository decisionRepository;

    @BeforeAll
    static void migrateSchema() {
        PostgresTestMigrationBootstrap.prepare(POSTGRES);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );

        var configuration = Flyway.configure()
                .dataSource(PostgresTestMigrationBootstrap.migrationDataSource(POSTGRES))
                .locations(PostgresTestMigrationBootstrap.productionLocation());

        PostgreSQLConfigurationExtension postgresql =
                configuration.getConfigurationExtension(
                        PostgreSQLConfigurationExtension.class
                );
        postgresql.setTransactionalLock(false);

        configuration.load().migrate();

        jdbc = new JdbcTemplate(dataSource);
        PostgresTestMigrationBootstrap.verifyMigratedSchema(jdbc, false);
        transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    @BeforeEach
    void seedTenantScope() {
        organizationId = UUID.randomUUID();
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            Instant now = ModelTestFixtures.NOW.minusSeconds(3_600);
            String organizationName = "Model Test " + organizationId;

            jdbc.update(
                    """
                    insert into organizations (
                        id, name, normalized_name, enabled,
                        created_at, updated_at, version, auth_version
                    ) values (
                        ?, ?, public.normalize_organization_name(?), true,
                        ?, ?, 0, 0
                    )
                    """,
                    organizationId,
                    organizationName,
                    organizationName,
                    Timestamp.from(now),
                    Timestamp.from(now)
            );

            jdbc.update(
                    """
                    insert into users (
                        id, organization_id, email, password_hash,
                        full_name, enabled, created_at, updated_at,
                        token_version, version, last_login_at, disabled_at
                    ) values (?, ?, ?, ?, ?, true, ?, ?, 0, 0, null, null)
                    """,
                    userId,
                    organizationId,
                    "model-" + userId + "@test.local",
                    "encoded",
                    "Model Test User",
                    Timestamp.from(now),
                    Timestamp.from(now)
            );

            jdbc.update(
                    """
                    insert into user_roles (user_id, role_id)
                    values (?, '22222222-2222-2222-2222-222222222222')
                    """,
                    userId
            );

            jdbc.update(
                    """
                    insert into chat_sessions (
                        id, user_id, organization_id, title,
                        created_at, updated_at, version,
                        archived_at, archived_by_user_id
                    ) values (?, ?, ?, ?, ?, ?, 0, null, null)
                    """,
                    chatId,
                    userId,
                    organizationId,
                    "Model Test Chat",
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
        });

        catalogRepository = new ModelCatalogRepository(jdbc);
        policyRepository = new OrganizationModelPolicyRepository(jdbc);
        decisionRepository = new ModelRouteDecisionRepository(jdbc);
    }

    @Test
    void catalogEffectiveSnapshotIsChosenBeforeRuntimeIdentityFiltering() {
        String modelKey = uniqueModelKey("snapshot");
        ModelCatalogEntry version1 = catalogEntry(
                modelKey,
                1,
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW.minusSeconds(120)
        );
        ModelCatalogEntry version2 = catalogEntry(
                modelKey,
                2,
                "openai",
                "gpt-remapped",
                ModelTestFixtures.NOW.minusSeconds(60)
        );

        catalogRepository.insert(version1, "FLAT_TOKEN_V1");
        catalogRepository.insert(version2, "FLAT_TOKEN_V1");

        assertThat(catalogRepository.findEffective(
                modelKey,
                ModelTestFixtures.NOW
        )).contains(version2);

        assertThat(catalogRepository.findEffectiveByRuntime(
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        )).noneMatch(entry -> entry.modelKey().equals(modelKey));
    }

    @Test
    void futureCatalogVersionIsLatestButNotEffectiveEarly() {
        String modelKey = uniqueModelKey("future");
        ModelCatalogEntry active = catalogEntry(
                modelKey,
                1,
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW.minusSeconds(60)
        );
        ModelCatalogEntry future = catalogEntry(
                modelKey,
                2,
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW.plusSeconds(3_600)
        );

        catalogRepository.insert(active, "FLAT_TOKEN_V1");
        catalogRepository.insert(future, "FLAT_TOKEN_V1");

        assertThat(catalogRepository.findLatest(modelKey))
                .contains(future);
        assertThat(catalogRepository.findEffective(
                modelKey,
                ModelTestFixtures.NOW
        )).contains(active);
    }

    @Test
    void futureOnlyRuntimeHistoryDoesNotCountAsEffectiveHistory() {
        String modelKey = uniqueModelKey("future-history");
        catalogRepository.insert(catalogEntry(
                modelKey,
                1,
                "openai",
                "future-runtime",
                ModelTestFixtures.NOW.plusSeconds(60)
        ), "FLAT_TOKEN_V1");

        assertThat(catalogRepository.hasEffectiveHistoryByRuntime(
                "openai",
                "future-runtime",
                ModelTestFixtures.NOW
        )).isFalse();

        assertThat(catalogRepository.hasEffectiveHistoryByRuntime(
                "openai",
                "future-runtime",
                ModelTestFixtures.NOW.plusSeconds(120)
        )).isTrue();
    }

    @Test
    void catalogRepositoryRoundTripsNonOpenAiProviderIdentity() {
        String modelKey = uniqueModelKey("anthropic");
        ModelCatalogEntry entry = catalogEntry(
                modelKey,
                1,
                "anthropic",
                "claude-test",
                ModelTestFixtures.NOW
        );

        catalogRepository.insert(entry, "FLAT_TOKEN_V1");

        ModelCatalogEntry loaded = catalogRepository
                .findLatest(modelKey)
                .orElseThrow();

        assertThat(loaded.provider()).isEqualTo("anthropic");
        assertThat(loaded.providerModelId()).isEqualTo("claude-test");
    }

    @Test
    void controlPlaneRowsAreAppendOnlyAtDatabaseBoundary() {
        ModelCatalogEntry catalog = catalogEntry(
                uniqueModelKey("immutable"),
                1,
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW
        );
        catalogRepository.insert(catalog, "FLAT_TOKEN_V1");

        OrganizationModelPolicy policy = policy(UUID.randomUUID());
        policyRepository.insert(policy);

        assertThatThrownBy(() -> jdbc.update(
                "update model_catalog_entries set display_name = ? where id = ?",
                "Changed",
                catalog.id()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "update organization_model_policies set enabled = false where id = ?",
                policy.id()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsPolicyListOverlapWhenRepositoryIsBypassed() {
        assertThatThrownBy(() -> jdbc.update(
                """
                insert into organization_model_policies (
                    id, organization_id, version, enabled,
                    allow_model_keys, deny_model_keys, default_model_key,
                    max_input_tokens, max_output_tokens,
                    max_request_cost_usd, monthly_budget_usd,
                    budget_enforcement, require_complete_pricing,
                    require_no_training, require_zero_data_retention,
                    created_by_user_id, created_at
                ) values (
                    ?, ?, 1, true,
                    array['openai:gpt-test']::text[],
                    array['openai:gpt-test']::text[],
                    null, null, null, null, null,
                    'SOFT', false, false, false, ?, ?
                )
                """,
                UUID.randomUUID(),
                organizationId,
                userId,
                Timestamp.from(ModelTestFixtures.NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repositoryRoundTripsPolicyArraysDeterministically() {
        OrganizationModelPolicy policy = new OrganizationModelPolicy(
                UUID.randomUUID(),
                organizationId,
                1,
                true,
                Set.of("z:model", "a:model"),
                Set.of("x:model"),
                "a:model",
                8_000,
                1_000,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BudgetEnforcement.HARD,
                true,
                true,
                true,
                userId,
                ModelTestFixtures.NOW
        );

        policyRepository.insert(policy);

        OrganizationModelPolicy loaded = policyRepository
                .findLatest(organizationId)
                .orElseThrow();

        assertThat(loaded.allowModelKeys())
                .containsExactly("a:model", "z:model");
        assertThat(loaded.denyModelKeys())
                .containsExactly("x:model");
        assertThat(loaded.rubBudget()).isNull();
    }

    @Test
    void rubBudgetSnapshotRoundTripsAndDatabaseRejectsRoundedUpCeiling() {
        Instant now = Instant.parse("2026-11-17T07:00:00Z");
        var snapshot = new RubBudgetSnapshot(new BigDecimal("5.00"), new BigDecimal("10000"),
                new BigDecimal("90.0000"), LocalDate.parse("2026-11-17"),
                LocalDate.parse("2026-11-16"), now.minusSeconds(1));
        UUID id = UUID.randomUUID();
        var valid = new OrganizationModelPolicy(id, organizationId, 1, true, Set.of(), Set.of(),
                null, null, null, snapshot.maxRequestCostUsd(), snapshot.monthlyBudgetUsd(),
                BudgetEnforcement.HARD, false, false, false, userId, now, snapshot);
        policyRepository.insert(valid);
        var loaded = policyRepository.findLatest(organizationId).orElseThrow();
        assertThat(loaded.rubBudget().rubPerUsd()).isEqualByComparingTo("90");
        assertThat(loaded.rubBudget().monthlyBudgetRub()).isEqualByComparingTo("10000");
        assertThat(loaded.maxRequestCostUsd()).isEqualByComparingTo("0.055555555555");
        var invalid = new OrganizationModelPolicy(UUID.randomUUID(), organizationId, 2, true, Set.of(), Set.of(),
                null, null, null, new BigDecimal("0.055555555556"), snapshot.monthlyBudgetUsd(),
                BudgetEnforcement.HARD, false, false, false, userId, now, snapshot);
        assertThatThrownBy(() -> policyRepository.insert(invalid)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(policyRepository.findLatest(organizationId).orElseThrow().id()).isEqualTo(id);
    }

    // These tests intentionally bypass Java BigDecimal/DTO validation. PostgreSQL
    // itself must reject malformed financial evidence, even for direct SQL writes.
    private enum BudgetColumn {
        MAX_USD, MONTHLY_USD, MAX_RUB, MONTHLY_RUB, RATE,
        QUOTE_DATE, EFFECTIVE_DATE, FETCHED_AT, CREATED_AT
    }

    private static final String V91_BUDGET_CHECK = "chk_model_policy_rub_budget_v91";
    private static final String V92_USD_CHECK = "chk_model_policy_usd_finite_v92";
    private static final String V92_DATE_CHECK = "chk_model_policy_rub_dates_finite_v92";

    private static String[] validRubBudget() {
        return new String[]{"0.055555555555", "111.111111111111", "5", "10000", "90",
                "2026-11-17", "2026-11-16", "2026-11-17T06:59:59Z", "2026-11-17T07:00:00Z"};
    }

    private static String[] validUsdBudget() {
        return new String[]{"5", "100", null, null, null, null, null, null, "2026-11-17T07:00:00Z"};
    }

    private static String[] withBudgetValue(String[] values, BudgetColumn column, String value) {
        String[] copy = values.clone();
        copy[column.ordinal()] = value;
        return copy;
    }

    private void insertBudgetSql(UUID id, int version, String[] values) {
        jdbc.update("""
                INSERT INTO public.organization_model_policies (
                    id, organization_id, version, enabled, budget_enforcement,
                    created_by_user_id, max_request_cost_usd, monthly_budget_usd,
                    max_request_cost_rub, monthly_budget_rub, rub_per_usd,
                    rub_quote_date, rub_effective_date, rub_fetched_at, created_at
                ) VALUES (?, ?, ?, true, 'HARD', ?, ?::numeric, ?::numeric,
                    ?::numeric, ?::numeric, ?::numeric, ?::date, ?::date,
                    ?::timestamptz, ?::timestamptz)
                """, id, organizationId, version, userId,
                values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8]);
    }

    private static void assertBudgetSqlRejected(String label, String sqlState,
                                                String constraint,
                                                ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).as(label)
                .isInstanceOf(DataAccessException.class)
                .satisfies(error -> {
                    SQLException sql = ModelIntegrationTestSupport.rootSqlException(error);
                    assertThat(sql.getSQLState()).as(label).isEqualTo(sqlState);
                    if (constraint != null) {
                        assertThat(sql.getMessage()).as(label).contains(constraint);
                    }
                });
    }

    private void rejectBudget(String label, String[] values, String constraint) {
        assertBudgetSqlRejected(label, "23514", constraint,
                () -> insertBudgetSql(UUID.randomUUID(), 1, values));
        assertThat(policyRepository.findLatest(organizationId)).as(label).isEmpty();
    }

    @Test
    void databaseRejectsIncompleteRubEvidenceAndMismatchedCurrencyPairs() {
        for (BudgetColumn column : List.of(BudgetColumn.RATE, BudgetColumn.QUOTE_DATE,
                BudgetColumn.EFFECTIVE_DATE, BudgetColumn.FETCHED_AT)) {
            rejectBudget("missing " + column, withBudgetValue(validRubBudget(), column, null),
                    V91_BUDGET_CHECK);
        }
        for (BudgetColumn column : List.of(BudgetColumn.MAX_RUB, BudgetColumn.MONTHLY_RUB,
                BudgetColumn.MAX_USD, BudgetColumn.MONTHLY_USD)) {
            rejectBudget("missing paired amount " + column,
                    withBudgetValue(validRubBudget(), column, null), V91_BUDGET_CHECK);
        }
        for (BudgetColumn column : List.of(BudgetColumn.MAX_RUB, BudgetColumn.MONTHLY_RUB,
                BudgetColumn.RATE, BudgetColumn.QUOTE_DATE, BudgetColumn.EFFECTIVE_DATE,
                BudgetColumn.FETCHED_AT)) {
            String[] rub = validRubBudget();
            rejectBudget("orphan RUB field " + column,
                    withBudgetValue(validUsdBudget(), column, rub[column.ordinal()]), V91_BUDGET_CHECK);
        }
        String[] noAmounts = validRubBudget();
        for (int i = 0; i < 4; i++) noAmounts[i] = null;
        rejectBudget("quote without any ceilings", noAmounts, V91_BUDGET_CHECK);
    }

    @Test
    void databaseRejectsNonFiniteAndNegativeFinancialValues() {
        for (String rate : List.of("0", "-1", "NaN")) {
            rejectBudget("invalid rate " + rate,
                    withBudgetValue(validRubBudget(), BudgetColumn.RATE, rate), V91_BUDGET_CHECK);
        }
        for (BudgetColumn column : List.of(BudgetColumn.MAX_RUB, BudgetColumn.MONTHLY_RUB)) {
            for (String amount : List.of("-0.000000000001", "NaN")) {
                rejectBudget("invalid RUB " + column + " " + amount,
                        withBudgetValue(validRubBudget(), column, amount), V91_BUDGET_CHECK);
            }
        }
        for (BudgetColumn column : List.of(BudgetColumn.MAX_USD, BudgetColumn.MONTHLY_USD)) {
            rejectBudget("USD-only NaN " + column,
                    withBudgetValue(validUsdBudget(), column, "NaN"), V92_USD_CHECK);
            // Both V91 and V92 reject NaN when a RUB snapshot exists.
            rejectBudget("RUB-backed USD NaN " + column,
                    withBudgetValue(validRubBudget(), column, "NaN"), null);
            rejectBudget("negative USD " + column,
                    withBudgetValue(validUsdBudget(), column, "-0.000000000001"),
                    "chk_organization_model_policies_limits");
        }
    }

    @Test
    void databaseRejectsImpossibleQuoteDatesAndTimes() {
        rejectBudget("effective date after quote",
                withBudgetValue(validRubBudget(), BudgetColumn.EFFECTIVE_DATE, "2026-11-18"), V91_BUDGET_CHECK);
        rejectBudget("effective date older than 14 days",
                withBudgetValue(validRubBudget(), BudgetColumn.EFFECTIVE_DATE, "2026-11-02"), V91_BUDGET_CHECK);
        rejectBudget("fetch falls on previous Moscow day",
                withBudgetValue(validRubBudget(), BudgetColumn.FETCHED_AT, "2026-11-16T20:59:59Z"), V91_BUDGET_CHECK);
        rejectBudget("fetch after creation",
                withBudgetValue(validRubBudget(), BudgetColumn.FETCHED_AT, "2026-11-17T07:00:00.000001Z"), V91_BUDGET_CHECK);
        for (String infinity : List.of("infinity", "-infinity")) {
            for (BudgetColumn column : List.of(BudgetColumn.QUOTE_DATE, BudgetColumn.EFFECTIVE_DATE,
                    BudgetColumn.FETCHED_AT, BudgetColumn.CREATED_AT)) {
                rejectBudget("non-finite " + column + " " + infinity,
                        withBudgetValue(validRubBudget(), column, infinity), null);
            }
            // All four matching infinite values satisfy the legacy relative
            // chronology checks; explicitly exercise the new finite constraint.
            String[] infinite = validRubBudget();
            for (int i = BudgetColumn.QUOTE_DATE.ordinal(); i <= BudgetColumn.CREATED_AT.ordinal(); i++) {
                infinite[i] = infinity;
            }
            rejectBudget("coherent infinite evidence " + infinity, infinite, V92_DATE_CHECK);
        }
        rejectBudget("infinite creation with otherwise valid quote",
                withBudgetValue(validRubBudget(), BudgetColumn.CREATED_AT, "infinity"), V92_DATE_CHECK);
    }

    @Test
    void databaseRejectsOverflowAndUnsupportedNumericInfinity() {
        for (BudgetColumn column : List.of(BudgetColumn.MAX_USD, BudgetColumn.MONTHLY_USD,
                BudgetColumn.MAX_RUB, BudgetColumn.MONTHLY_RUB, BudgetColumn.RATE)) {
            String tooLarge = column == BudgetColumn.RATE ? "10000000000" : "1000000000000000000";
            for (String value : List.of(tooLarge, "Infinity", "-Infinity")) {
                assertBudgetSqlRejected("numeric overflow " + column + " " + value, "22003", null,
                        () -> insertBudgetSql(UUID.randomUUID(), 1,
                                withBudgetValue(validRubBudget(), column, value)));
            }
        }
        // An otherwise storable RUB amount can imply an unrepresentable USD
        // ceiling when divided by a tiny rate. Never accept a clipped ceiling.
        String[] overflow = validRubBudget();
        overflow[BudgetColumn.MAX_RUB.ordinal()] = "999999999999999999.999999999999";
        overflow[BudgetColumn.RATE.ordinal()] = "0.00000001";
        rejectBudget("conversion outside USD range", overflow, V91_BUDGET_CHECK);
        assertThat(policyRepository.findLatest(organizationId)).isEmpty();
    }

    @Test
    void databaseAcceptsZeroAndSingleCeilingsExactBoundariesAndHistoricalSnapshots() {
        String[] usd = validUsdBudget();
        usd[0] = null;
        usd[1] = "0";
        insertBudgetSql(UUID.randomUUID(), 1, usd);
        assertThat(policyRepository.findLatest(organizationId).orElseThrow().rubBudget()).isNull();

        String[] maxOnly = validRubBudget();
        maxOnly[0] = "0";
        maxOnly[1] = null;
        maxOnly[2] = "0.000000000001";
        maxOnly[3] = null;
        maxOnly[6] = "2026-11-03"; // exactly 14 days older
        maxOnly[7] = "2026-11-16T21:00:00Z"; // Moscow midnight
        insertBudgetSql(UUID.randomUUID(), 2, maxOnly);
        var loaded = policyRepository.findLatest(organizationId).orElseThrow();
        assertThat(loaded.maxRequestCostUsd()).isZero();
        assertThat(loaded.monthlyBudgetUsd()).isNull();

        String[] monthlyOnly = validRubBudget();
        monthlyOnly[0] = null;
        monthlyOnly[1] = "999999999999999999.999999999999";
        monthlyOnly[2] = null;
        monthlyOnly[3] = monthlyOnly[1];
        monthlyOnly[4] = "1";
        monthlyOnly[8] = "2026-11-20T07:00:00Z"; // unchanged historical evidence
        insertBudgetSql(UUID.randomUUID(), 3, monthlyOnly);
        loaded = policyRepository.findLatest(organizationId).orElseThrow();
        assertThat(loaded.monthlyBudgetUsd()).isEqualByComparingTo(monthlyOnly[1]);
        assertThat(loaded.rubBudget().rubPerUsd()).isEqualByComparingTo("1");
        assertThat(loaded.rubBudget().quoteDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(loaded.maxRequestCostUsd()).isNull();
    }

    @Test
    void persistedRubQuoteCannotBeRewrittenOrDeleted() {
        UUID id = UUID.randomUUID();
        insertBudgetSql(id, 1, validRubBudget());
        var original = policyRepository.findLatest(organizationId).orElseThrow();
        assertBudgetSqlRejected("mutate valid quote", "23514", "immutable control-plane provenance",
                () -> jdbc.update("""
                        UPDATE public.organization_model_policies
                        SET rub_per_usd = 100, max_request_cost_usd = 0.05, monthly_budget_usd = 100
                        WHERE id = ?
                        """, id));
        assertBudgetSqlRejected("delete quote", "23514", "immutable control-plane provenance",
                () -> jdbc.update("DELETE FROM public.organization_model_policies WHERE id = ?", id));
        assertThat(policyRepository.findLatest(organizationId).orElseThrow()).isEqualTo(original);
    }

    @Test
    void allowedRoutingDecisionAndExactChatTurnCommitAtomicallyUnderV45Fk() {
        String modelKey = uniqueModelKey("route");
        ModelCatalogEntry entry = catalogEntry(
                modelKey,
                1,
                "openai",
                "gpt-test",
                ModelTestFixtures.NOW.minusSeconds(1)
        );
        catalogRepository.insert(entry, "FLAT_TOKEN_V1");
        insertAllowAllRoutingPolicy();

        RuntimeModelStatusService runtimeStatus =
                mock(RuntimeModelStatusService.class);
        when(runtimeStatus.current()).thenReturn(ModelTestFixtures.freeRuntime());

        ModelRoutingService routing = routingService(
                runtimeStatus,
                ModelTestFixtures.CLOCK
        );

        UUID turnId = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();

        UUID decisionId = transaction.execute(status -> {
            var result = routing.decide(
                    routeRequest(
                            turnId,
                            clientRequestId,
                            modelKey
                    ),
                    principal("ROLE_USER")
            );

            insertUserMessage(
                    userMessageId,
                    clientRequestId
            );
            insertProcessingTurn(
                    turnId,
                    clientRequestId,
                    userMessageId,
                    result.decisionId(),
                    result.provider(),
                    result.providerModelId()
            );
            return result.decisionId();
        });

        assertThat(decisionId).isNotNull();
        assertThat(decisionRepository.findById(decisionId))
                .get()
                .extracting(
                        ModelRouteDecision::chatTurnId,
                        ModelRouteDecision::selectedProvider,
                        ModelRouteDecision::selectedProviderModelId
                )
                .containsExactly(
                        turnId,
                        "openai",
                        "gpt-test"
                );

        assertThat(routing.findDecision(
                decisionId,
                principal("ROLE_ADMIN")
        ).decisionSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void deniedRouteDecisionIsAppendOnlyAndHashVerifiesAfterDatabaseRoundTrip() {
        insertAllowAllRoutingPolicy();

        RuntimeModelStatusService runtimeStatus =
                mock(RuntimeModelStatusService.class);
        when(runtimeStatus.current()).thenReturn(ModelTestFixtures.freeRuntime());

        ModelRoutingService routing = routingService(
                runtimeStatus,
                ModelTestFixtures.CLOCK
        );

        UUID clientRequestId = UUID.randomUUID();
        UUID decisionId = transaction.execute(status -> {
            try {
                routing.decide(
                        routeRequest(
                                UUID.randomUUID(),
                                clientRequestId,
                                uniqueModelKey("missing")
                        ),
                        principal("ROLE_USER")
                );
                throw new AssertionError(
                        "Missing catalog model должен быть отклонён"
                );
            } catch (ModelRouteDeniedException exception) {
                return exception.getDecisionId();
            }
        });

        assertThat(decisionId).isNotNull();
        assertThat(routing.findDecision(
                decisionId,
                principal("ROLE_ADMIN")
        ).outcome()).isEqualTo(ModelRouteOutcome.DENIED);

        assertThatThrownBy(() -> jdbc.update(
                "update model_route_decisions set reason = ? where id = ?",
                ModelRouteReason.MODEL_DISABLED.name(),
                decisionId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void decisionHashSurvivesPostgresTimestampPrecisionRoundTrip() {
        insertAllowAllRoutingPolicy();

        RuntimeModelStatusService runtimeStatus =
                mock(RuntimeModelStatusService.class);
        when(runtimeStatus.current()).thenReturn(ModelTestFixtures.freeRuntime());

        Instant highPrecisionNow =
                Instant.parse("2026-08-28T12:00:00.123456789Z");
        Clock highPrecisionClock = Clock.fixed(
                highPrecisionNow,
                ZoneOffset.UTC
        );
        ModelRoutingService routing = routingService(
                runtimeStatus,
                highPrecisionClock
        );

        UUID decisionId = transaction.execute(status -> {
            try {
                routing.decide(
                        routeRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                uniqueModelKey("timestamp-missing")
                        ),
                        principal("ROLE_USER")
                );
                throw new AssertionError(
                        "Missing model должен быть отклонён"
                );
            } catch (ModelRouteDeniedException exception) {
                return exception.getDecisionId();
            }
        });

        assertThat(decisionId).isNotNull();

        var response = routing.findDecision(
                decisionId,
                principal("ROLE_ADMIN")
        );

        assertThat(response.decisionSha256())
                .matches("[0-9a-f]{64}");
        assertThat(response.createdAt().getNano() % 1_000)
                .isZero();
    }

    @Test
    void catalogServiceCreatesSecondVersionWhenExpectationMatches() {
        ModelCatalogService service = new ModelCatalogService(
                catalogRepository,
                mock(RuntimeModelStatusService.class),
                mock(AuditEventService.class),
                ModelTestFixtures.CLOCK
        );
        String modelKey = uniqueModelKey("sequential-version");

        var first = Objects.requireNonNull(
                transaction.execute(ignoredStatus ->
                        service.createVersion(
                                freeCatalogRequest(modelKey, 0),
                                principal("ROLE_SUPER_ADMIN")
                        )
                ),
                "First catalog version must be created"
        );

        var second = Objects.requireNonNull(
                transaction.execute(ignoredStatus ->
                        service.createVersion(
                                freeCatalogRequest(modelKey, 1),
                                principal("ROLE_SUPER_ADMIN")
                        )
                ),
                "Second catalog version must be created"
        );

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(catalogRepository.findLatest(modelKey)
                .orElseThrow()
                .version()).isEqualTo(2);
    }

    @Test
    void catalogVersionAllocationIsSerializedAcrossConcurrentTransactions()
            throws Exception {
        ModelCatalogService service = new ModelCatalogService(
                catalogRepository,
                mock(RuntimeModelStatusService.class),
                mock(AuditEventService.class),
                ModelTestFixtures.CLOCK
        );
        String modelKey = uniqueModelKey("concurrent");
        CreateModelCatalogVersionRequest request =
                freeCatalogRequest(modelKey, 0);

        List<ConcurrentOutcome> outcomes = runTwoConcurrent(() ->
                transaction.execute(status ->
                        service.createVersion(
                                request,
                                principal("ROLE_SUPER_ADMIN")
                        )
                )
        );

        assertThat(outcomes)
                .filteredOn(ConcurrentSuccess.class::isInstance)
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome ->
                        outcome instanceof ConcurrentFailure(Throwable cause)
                                && cause instanceof ConflictException
                )
                .hasSize(1);
        assertThat(catalogRepository.findLatest(modelKey)
                .orElseThrow()
                .version()).isEqualTo(1);
    }

    @Test
    void policyServiceCreatesSecondVersionWhenExpectationMatches() {
        var confirmations = confirmationService();
        OrganizationModelPolicyService service =
                new OrganizationModelPolicyService(
                        policyRepository,
                        mock(AuditEventService.class),
                        ModelTestFixtures.CLOCK, confirmations
                );

        var first = Objects.requireNonNull(
                transaction.execute(ignoredStatus ->
                        service.createVersion(
                                organizationId,
                                confirmedRequest(confirmations, 0),
                                principal("ROLE_ADMIN")
                        )
                ),
                "First policy version must be created"
        );

        var second = Objects.requireNonNull(
                transaction.execute(ignoredStatus ->
                        service.createVersion(
                                organizationId,
                                confirmedRequest(confirmations, 1),
                                principal("ROLE_ADMIN")
                        )
                ),
                "Second policy version must be created"
        );

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(policyRepository.findLatest(organizationId)
                .orElseThrow()
                .version()).isEqualTo(2);
    }

    @Test
    void policyVersionAllocationIsSerializedAcrossConcurrentTransactions()
            throws Exception {
        var confirmations = confirmationService();
        var audit = mock(AuditEventService.class);
        OrganizationModelPolicyService service =
                new OrganizationModelPolicyService(
                        policyRepository,
                        audit,
                        ModelTestFixtures.CLOCK, confirmations
                );
        CreateOrganizationModelPolicyVersionRequest request =
                transaction.execute(status -> confirmedRequest(confirmations, 0));

        List<ConcurrentOutcome> outcomes = runTwoConcurrent(() ->
                transaction.execute(status ->
                        service.createVersion(
                                organizationId,
                                request,
                                principal("ROLE_ADMIN")
                        )
                )
        );

        assertThat(outcomes)
                .filteredOn(ConcurrentSuccess.class::isInstance)
                .hasSize(2); // Идентичный retry возвращает уже созданную версию.
        assertThat(policyRepository.findLatest(organizationId)
                .orElseThrow()
                .version()).isEqualTo(1);
        verify(audit).recordPrincipalSnapshot(any(SafeAiUserPrincipal.class), eq(organizationId),
                eq(AuditEventType.MODEL_POLICY_VERSION_CREATED), any(AuditDetails.class));
    }

    @Test
    void policyProofRejectsDirectSaveAndDifferentPayloadAndSurvivesRolledBackSave() {
        var confirmations = confirmationService();
        var actor = principal("ROLE_ADMIN");
        var audit = mock(AuditEventService.class);
        var service = new OrganizationModelPolicyService(policyRepository, audit, ModelTestFixtures.CLOCK, confirmations);
        assertThatThrownBy(() -> transaction.execute(status -> service.createVersion(organizationId, policyRequest(0), actor)))
                .isInstanceOf(ConflictException.class);
        assertThat(policyRepository.findLatest(organizationId)).isEmpty();

        var request = transaction.execute(status -> confirmedRequest(confirmations, 0));
        assertThat(request).isNotNull();
        var changed = new CreateOrganizationModelPolicyVersionRequest(0, false, request.allowModelKeys(),
                request.denyModelKeys(), request.defaultModelKey(), request.maxInputTokens(), request.maxOutputTokens(),
                request.maxRequestCostUsd(), request.monthlyBudgetUsd(), request.budgetEnforcement(),
                request.requireCompletePricing(), request.requireNoTraining(), request.requireZeroDataRetention(),
                request.rubBudget(), request.previewConfirmationId(), false);
        assertThatThrownBy(() -> transaction.execute(status -> service.createVersion(organizationId, changed, actor)))
                .isInstanceOf(ConflictException.class);
        assertThat(policyRepository.findLatest(organizationId)).isEmpty();

        assertThatThrownBy(() -> transaction.execute(status -> {
            service.createVersion(organizationId, request, actor);
            throw new IllegalStateException("Forced rollback after insert and proof completion");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Forced rollback after insert and proof completion");
        assertThat(policyRepository.findLatest(organizationId)).isEmpty();
        var saved = transaction.execute(status -> service.createVersion(organizationId, request, actor));
        var retry = transaction.execute(status -> service.createVersion(organizationId, request, actor));
        assertThat(retry).isEqualTo(saved);
        assertThat(policyRepository.findLatest(organizationId).orElseThrow().version()).isEqualTo(1);
        assertThatThrownBy(() -> transaction.execute(status -> service.createVersion(organizationId, changed, actor)))
                .isInstanceOf(ConflictException.class);
    }

    private ModelPolicyPreviewConfirmationService confirmationService() {
        var simulator = mock(ModelPolicyPreviewService.class);
        when(simulator.preview(any(), any(), any())).thenAnswer(call -> {
            CreateOrganizationModelPolicyVersionRequest draft = call.getArgument(1);
            return new ModelPolicyPreviewResponse(call.getArgument(0), draft.expectedPreviousVersion(),
                    draft.enabled(), ModelTestFixtures.NOW, "mock", "mock-safeai", ModelRouteOutcome.ALLOWED,
                    ModelRouteReason.RUNTIME_ONLY_MATCH, null, 0, false, List.of());
        });
        return new ModelPolicyPreviewConfirmationService(simulator,
                new ModelPolicyPreviewConfirmationRepository(jdbc), policyRepository, ModelTestFixtures.CLOCK);
    }

    private CreateOrganizationModelPolicyVersionRequest confirmedRequest(
            ModelPolicyPreviewConfirmationService confirmations, int version) {
        var draft = policyRequest(version);
        var preview = confirmations.preview(organizationId, draft, principal("ROLE_ADMIN"));
        return new CreateOrganizationModelPolicyVersionRequest(version, draft.enabled(), draft.allowModelKeys(),
                draft.denyModelKeys(), draft.defaultModelKey(), draft.maxInputTokens(), draft.maxOutputTokens(),
                draft.maxRequestCostUsd(), draft.monthlyBudgetUsd(), draft.budgetEnforcement(),
                draft.requireCompletePricing(), draft.requireNoTraining(), draft.requireZeroDataRetention(),
                draft.rubBudget(), preview.previewConfirmationId(), false);
    }

    private void insertAllowAllRoutingPolicy() {
        ModelIntegrationTestSupport.insertAllowAllPolicy(
                jdbc,
                organizationId,
                userId,
                1,
                null,
                null,
                ModelTestFixtures.NOW.minusSeconds(30)
        );
    }

    private ModelRoutingService routingService(
            RuntimeModelStatusService runtimeStatus,
            Clock routingClock
    ) {
        return ModelIntegrationTestSupport.routingService(
                jdbc,
                catalogRepository,
                policyRepository,
                decisionRepository,
                runtimeStatus,
                mock(AuditEventService.class),
                routingClock
        );
    }

    private List<ConcurrentOutcome> runTwoConcurrent(
            ThrowingRunnable operation
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<ConcurrentOutcome> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent start gate timed out");
                }
                try {
                    operation.run();
                    return new ConcurrentSuccess();
                } catch (Throwable throwable) {
                    return new ConcurrentFailure(throwable);
                }
            };

            Future<ConcurrentOutcome> first = executor.submit(task);
            Future<ConcurrentOutcome> second = executor.submit(task);

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("Both concurrent workers must become ready")
                    .isTrue();
            start.countDown();

            return List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
        }
    }

    private void insertUserMessage(
            UUID messageId,
            UUID clientRequestId
    ) {
        jdbc.update(
                """
                insert into chat_messages (
                    id, session_id, organization_id, role, content,
                    client_request_id, status, usage_status,
                    pricing_status, created_at
                ) values (
                    ?, ?, ?, 'USER', ?, ?, 'COMPLETED',
                    'NOT_APPLICABLE', 'NOT_APPLICABLE', ?
                )
                """,
                messageId,
                chatId,
                organizationId,
                "hello",
                clientRequestId,
                Timestamp.from(ModelTestFixtures.NOW)
        );
    }

    private void insertProcessingTurn(
            UUID turnId,
            UUID clientRequestId,
            UUID userMessageId,
            UUID decisionId,
            String provider,
            String requestedModel
    ) {
        Instant now = ModelTestFixtures.NOW;
        jdbc.update(
                """
                insert into chat_turns (
                    id, session_id, organization_id, user_id,
                    client_request_id, request_content_hash,
                    provider_operation_id, user_message_id,
                    state, processing_token, lease_until,
                    provider, requested_model,
                    knowledge_mode, knowledge_base_id,
                    outcome_ambiguous, created_at, updated_at,
                    completed_at, version, model_route_decision_id
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    'PROCESSING', ?, ?, ?, ?,
                    'GENERAL', null,
                    false, ?, ?, null, 0, ?
                )
                """,
                turnId,
                chatId,
                organizationId,
                userId,
                clientRequestId,
                ModelTestFixtures.REQUEST_HASH,
                UUID.randomUUID(),
                userMessageId,
                UUID.randomUUID(),
                Timestamp.from(now.plusSeconds(180)),
                provider,
                requestedModel,
                Timestamp.from(now),
                Timestamp.from(now),
                decisionId
        );
    }

    private ModelCatalogEntry catalogEntry(
            String modelKey,
            int version,
            String provider,
            String providerModelId,
            Instant effectiveFrom
    ) {
        return new ModelCatalogEntry(
                UUID.randomUUID(),
                modelKey,
                version,
                provider,
                providerModelId,
                providerModelId,
                ModelLifecycle.ACTIVE,
                32_000,
                4_096,
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.FREE,
                true,
                ZERO_MONEY,
                null,
                null,
                ZERO_MONEY,
                "{}",
                null,
                effectiveFrom,
                ModelCatalogSource.MANUAL,
                userId,
                ModelTestFixtures.NOW
        );
    }

    private OrganizationModelPolicy policy(UUID id) {
        return new OrganizationModelPolicy(
                id,
                organizationId,
                1,
                true,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false,
                userId,
                ModelTestFixtures.NOW
        );
    }

    private SafeAiUserPrincipal principal(String authority) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId,
                organizationId,
                0L,
                0L,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }

    private ModelRouteRequest routeRequest(
            UUID turnId,
            UUID clientRequestId,
            String requestedModelKey
    ) {
        return new ModelRouteRequest(
                organizationId,
                userId,
                chatId,
                turnId,
                clientRequestId,
                ModelTestFixtures.REQUEST_HASH,
                requestedModelKey,
                "hello",
                List.of(),
                Set.of(),
                0L
        );
    }

    private static CreateModelCatalogVersionRequest freeCatalogRequest(
            String modelKey,
            int expectedPreviousVersion
    ) {
        return new CreateModelCatalogVersionRequest(
                modelKey,
                "openai",
                "gpt-test",
                "GPT Test",
                ModelLifecycle.ACTIVE,
                32_000,
                4_096,
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                "{}",
                null,
                null,
                expectedPreviousVersion
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest policyRequest(
            int expectedPreviousVersion
    ) {
        return new CreateOrganizationModelPolicyVersionRequest(
                expectedPreviousVersion,
                true,
                Set.of(),
                Set.of(),
                null,
                8_000,
                1_000,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false
        );
    }

    private static String uniqueModelKey(String prefix) {
        return "test:" + prefix + ":" + UUID.randomUUID();
    }

    private sealed interface ConcurrentOutcome
            permits ConcurrentSuccess, ConcurrentFailure {
    }

    private record ConcurrentSuccess() implements ConcurrentOutcome {
    }

    private record ConcurrentFailure(Throwable cause)
            implements ConcurrentOutcome {
        private ConcurrentFailure {
            Objects.requireNonNull(cause, "cause");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
