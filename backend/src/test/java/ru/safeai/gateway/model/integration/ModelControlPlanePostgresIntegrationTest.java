package ru.safeai.gateway.model.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.safeai.gateway.audit.service.AuditEventService;
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
import ru.safeai.gateway.model.dto.CreateModelCatalogVersionRequest;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
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
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                );

        /*
         * This test creates Flyway manually, outside Spring Boot.
         * Spring's PostgreSQL transactional-lock setting is therefore not
         * propagated to this Flyway instance automatically.
         *
         * V21 and other production migrations intentionally use PostgreSQL
         * CREATE/DROP INDEX CONCURRENTLY. Those statements are
         * non-transactional and require Flyway's PostgreSQL transactional
         * advisory lock to be disabled.
         *
         * Use Flyway's public PostgreSQL configuration extension so a future
         * dependency/API change fails at compilation time rather than
         * silently changing migration semantics.
         */
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");

        PostgreSQLConfigurationExtension postgresql =
                configuration.getConfigurationExtension(
                        PostgreSQLConfigurationExtension.class
                );
        postgresql.setTransactionalLock(false);

        configuration
                .load()
                .migrate();

        jdbc = new JdbcTemplate(dataSource);
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

            String organizationName =
                    "Model Test " + organizationId;

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

        catalogRepository.insert(version1);
        catalogRepository.insert(version2);

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

        catalogRepository.insert(active);
        catalogRepository.insert(future);

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
        ));

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

        catalogRepository.insert(entry);

        ModelCatalogEntry loaded = catalogRepository
                .findLatest(modelKey)
                .orElseThrow();

        assertThat(loaded.provider())
                .isEqualTo("anthropic");
        assertThat(loaded.providerModelId())
                .isEqualTo("claude-test");
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
        catalogRepository.insert(catalog);

        OrganizationModelPolicy policy = policy(
                UUID.randomUUID()
        );
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
        catalogRepository.insert(entry);

        RuntimeModelStatusService runtimeStatus =
                mock(RuntimeModelStatusService.class);
        when(runtimeStatus.current()).thenReturn(runtime());

        ModelRoutingService routing = new ModelRoutingService(
                catalogRepository,
                policyRepository,
                decisionRepository,
                runtimeStatus,
                mock(AuditEventService.class)
        );

        UUID turnId = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();

        UUID decisionId = transaction.execute(status -> {
            var result = routing.decide(
                    new ModelRouteRequest(
                            organizationId,
                            userId,
                            chatId,
                            turnId,
                            clientRequestId,
                            ModelTestFixtures.REQUEST_HASH,
                            modelKey,
                            "hello",
                            List.of(),
                            Set.of()
                    ),
                    principal("ROLE_USER"),
                    ModelTestFixtures.NOW
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

        assertThat(decisionId)
                .isNotNull();
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

        assertThat(
                routing.findDecision(
                        decisionId,
                        principal("ROLE_ADMIN")
                ).decisionSha256()
        ).matches("[0-9a-f]{64}");
    }

    @Test
    void deniedRouteDecisionIsAppendOnlyAndHashVerifiesAfterDatabaseRoundTrip() {
        RuntimeModelStatusService runtimeStatus =
                mock(RuntimeModelStatusService.class);
        when(runtimeStatus.current()).thenReturn(runtime());

        ModelRoutingService routing = new ModelRoutingService(
                catalogRepository,
                policyRepository,
                decisionRepository,
                runtimeStatus,
                mock(AuditEventService.class)
        );

        UUID clientRequestId = UUID.randomUUID();
        UUID decisionId = transaction.execute(status -> {
            try {
                routing.decide(
                        new ModelRouteRequest(
                                organizationId,
                                userId,
                                chatId,
                                UUID.randomUUID(),
                                clientRequestId,
                                ModelTestFixtures.REQUEST_HASH,
                                uniqueModelKey("missing"),
                                "hello",
                                List.of(),
                                Set.of()
                        ),
                        principal("ROLE_USER"),
                        ModelTestFixtures.NOW
                );
                throw new AssertionError(
                        "Missing catalog model должен быть отклонён"
                );
            } catch (ModelRouteDeniedException exception) {
                return exception.getDecisionId();
            }
        });

        assertThat(decisionId)
                .isNotNull();
        assertThat(
                routing.findDecision(
                        decisionId,
                        principal("ROLE_ADMIN")
                ).outcome()
        ).isEqualTo(ModelRouteOutcome.DENIED);

        assertThatThrownBy(() -> jdbc.update(
                "update model_route_decisions set reason = ? where id = ?",
                ModelRouteReason.MODEL_DISABLED.name(),
                decisionId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void decisionHashSurvivesPostgresTimestampPrecisionRoundTrip() {
        RuntimeModelStatusService runtimeStatus =
                mock(RuntimeModelStatusService.class);
        when(runtimeStatus.current()).thenReturn(runtime());

        ModelRoutingService routing = new ModelRoutingService(
                catalogRepository,
                policyRepository,
                decisionRepository,
                runtimeStatus,
                mock(AuditEventService.class)
        );

        Instant highPrecisionNow =
                Instant.parse("2026-08-28T12:00:00.123456789Z");
        UUID decisionId = transaction.execute(status -> {
            try {
                routing.decide(
                        new ModelRouteRequest(
                                organizationId,
                                userId,
                                chatId,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                ModelTestFixtures.REQUEST_HASH,
                                uniqueModelKey("timestamp-missing"),
                                "hello",
                                List.of(),
                                Set.of()
                        ),
                        principal("ROLE_USER"),
                        highPrecisionNow
                );
                throw new AssertionError(
                        "Missing model должен быть отклонён"
                );
            } catch (ModelRouteDeniedException exception) {
                return exception.getDecisionId();
            }
        });

        assertThat(decisionId)
                .isNotNull();

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
                                freeCatalogRequest(
                                        modelKey,
                                        0
                                ),
                                principal("ROLE_SUPER_ADMIN")
                        )
                ),
                "First catalog version must be created"
        );

        var second = Objects.requireNonNull(
                transaction.execute(ignoredStatus ->
                        service.createVersion(
                                freeCatalogRequest(
                                        modelKey,
                                        1
                                ),
                                principal("ROLE_SUPER_ADMIN")
                        )
                ),
                "Second catalog version must be created"
        );

        assertThat(first.version())
                .isEqualTo(1);
        assertThat(second.version())
                .isEqualTo(2);

        assertThat(catalogRepository.findLatest(modelKey))
                .get()
                .extracting(ModelCatalogEntry::version)
                .isEqualTo(2);
    }

    @Test
    void catalogVersionAllocationIsSerializedAcrossConcurrentTransactions()
            throws Exception {
        AuditEventService audit = mock(AuditEventService.class);
        ModelCatalogService service = new ModelCatalogService(
                catalogRepository,
                mock(RuntimeModelStatusService.class),
                audit,
                ModelTestFixtures.CLOCK
        );
        String modelKey = uniqueModelKey("concurrent");
        CreateModelCatalogVersionRequest request =
                freeCatalogRequest(modelKey, 0);

        List<Object> outcomes = runTwoConcurrent(() ->
                transaction.execute(status ->
                        service.createVersion(
                                request,
                                principal("ROLE_SUPER_ADMIN")
                        )
                )
        );

        assertThat(outcomes)
                .filteredOn(value ->
                        value instanceof ru.safeai.gateway.model.dto.ModelCatalogEntryResponse
                )
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(value ->
                        value instanceof ru.safeai.gateway.common.exception.ConflictException
                )
                .hasSize(1);
        assertThat(catalogRepository.findLatest(modelKey))
                .get()
                .extracting(ModelCatalogEntry::version)
                .isEqualTo(1);
    }

    @Test
    void policyServiceCreatesSecondVersionWhenExpectationMatches() {
        OrganizationModelPolicyService service =
                new OrganizationModelPolicyService(
                        policyRepository,
                        mock(AuditEventService.class),
                        ModelTestFixtures.CLOCK
                );

        var first = Objects.requireNonNull(
                transaction.execute(ignoredStatus ->
                        service.createVersion(
                                organizationId,
                                policyRequest(0),
                                principal("ROLE_ADMIN")
                        )
                ),
                "First policy version must be created"
        );

        var second = Objects.requireNonNull(
                transaction.execute(ignoredStatus ->
                        service.createVersion(
                                organizationId,
                                policyRequest(1),
                                principal("ROLE_ADMIN")
                        )
                ),
                "Second policy version must be created"
        );

        assertThat(first.version())
                .isEqualTo(1);
        assertThat(second.version())
                .isEqualTo(2);

        assertThat(policyRepository.findLatest(organizationId))
                .get()
                .extracting(OrganizationModelPolicy::version)
                .isEqualTo(2);
    }

    @Test
    void policyVersionAllocationIsSerializedAcrossConcurrentTransactions()
            throws Exception {
        OrganizationModelPolicyService service =
                new OrganizationModelPolicyService(
                        policyRepository,
                        mock(AuditEventService.class),
                        ModelTestFixtures.CLOCK
                );
        CreateOrganizationModelPolicyVersionRequest request =
                policyRequest(0);

        List<Object> outcomes = runTwoConcurrent(() ->
                transaction.execute(status ->
                        service.createVersion(
                                organizationId,
                                request,
                                principal("ROLE_ADMIN")
                        )
                )
        );

        assertThat(outcomes)
                .filteredOn(value ->
                        value instanceof ru.safeai.gateway.model.dto.OrganizationModelPolicyResponse
                )
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(value ->
                        value instanceof ru.safeai.gateway.common.exception.ConflictException
                )
                .hasSize(1);
        assertThat(policyRepository.findLatest(organizationId))
                .get()
                .extracting(OrganizationModelPolicy::version)
                .isEqualTo(1);
    }

    private List<Object> runTwoConcurrent(
            ThrowingSupplier supplier
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Object> task = () -> {
                ready.countDown();
                start.await();
                try {
                    return supplier.get();
                } catch (Throwable throwable) {
                    return throwable;
                }
            };

            Future<Object> first = executor.submit(task);
            Future<Object> second = executor.submit(task);
            ready.await();
            start.countDown();

            return List.of(
                    first.get(),
                    second.get()
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
                    outcome_ambiguous, created_at, updated_at,
                    completed_at, version, model_route_decision_id
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    'PROCESSING', ?, ?, ?, ?,
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

    private OrganizationModelPolicy policy(
            UUID id
    ) {
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

    private SafeAiUserPrincipal principal(
            String authority
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId,
                organizationId,
                0L,
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                authority
                        )
                )
        );
    }

    private static RuntimeModelStatusResponse runtime() {
        return new RuntimeModelStatusResponse(
                "openai",
                "gpt-test",
                true,
                "SINGLE_PROVIDER_STATIC",
                32_000,
                4_096,
                false,
                false,
                false,
                "NOT_DECLARED",
                "NOT_PROBED",
                "FREE",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
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

    private static String uniqueModelKey(
            String prefix
    ) {
        return "test:" + prefix + ":" + UUID.randomUUID();
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
