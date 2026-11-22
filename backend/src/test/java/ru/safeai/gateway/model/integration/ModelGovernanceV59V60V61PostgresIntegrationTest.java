package ru.safeai.gateway.model.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.integration.AbstractChatPostgresIntegrationTest;
import ru.safeai.gateway.chat.repository.ChatQuotaRepository;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.repository.UsagePhysicalSpendRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.awaitility.Awaitility.await;


/**
 * Cross-version regression coverage for V59-V61 governance hardening and the
 * V60 physical-cost commitment model, executed under the current V64/V65
 * prepared-request fence.
 */
@Tag("integration")
@SpringBootTest(properties = {
    "safeai.chat.recovery.enabled=false",
    "safeai.usage.rollup.enabled=false",
    "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelGovernanceV59V60V61PostgresIntegrationTest
    extends AbstractChatPostgresIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private ru.safeai.gateway.ai.execution.AiExecutionAttemptRecorder attemptRecorder;

    private static final Instant SEPTEMBER_START =
        Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant OCTOBER_START =
        Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant AUGUST_START =
        Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void v59RejectsCommaSmugglingAndV61RejectsDuplicatePolicyKeys() {
        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                insert into public.organization_model_policies (
                    id, organization_id, version, enabled,
                    allow_model_keys, deny_model_keys, default_model_key,
                    max_input_tokens, max_output_tokens,
                    max_request_cost_usd, monthly_budget_usd,
                    budget_enforcement, require_complete_pricing,
                    require_no_training, require_zero_data_retention,
                    created_by_user_id, created_at
                ) values (?, ?, 91, true,
                          array['foo,bar']::text[], '{}'::text[], null,
                          null, null, null, null, 'SOFT', false, false, false, ?, ?)
                """,
            UUID.randomUUID(),
            ORGANIZATION_ID,
            USER_ID,
            timestamp(ModelTestFixtures.NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                insert into public.organization_model_policies (
                    id, organization_id, version, enabled,
                    allow_model_keys, deny_model_keys, default_model_key,
                    max_input_tokens, max_output_tokens,
                    max_request_cost_usd, monthly_budget_usd,
                    budget_enforcement, require_complete_pricing,
                    require_no_training, require_zero_data_retention,
                    created_by_user_id, created_at
                ) values (?, ?, 92, true,
                          array['openai:gpt-test','openai:gpt-test']::text[],
                          '{}'::text[], null,
                          null, null, null, null, 'SOFT', false, false, false, ?, ?)
                """,
            UUID.randomUUID(),
            ORGANIZATION_ID,
            USER_ID,
            timestamp(ModelTestFixtures.NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                insert into public.organization_model_policies (
                    id, organization_id, version, enabled,
                    allow_model_keys, deny_model_keys, default_model_key,
                    max_input_tokens, max_output_tokens,
                    max_request_cost_usd, monthly_budget_usd,
                    budget_enforcement, require_complete_pricing,
                    require_no_training, require_zero_data_retention,
                    created_by_user_id, created_at
                ) values (?, ?, 93, true, '{}'::text[],
                          array['blocked:model','blocked:model']::text[], null,
                          null, null, null, null, 'SOFT', false, false, false, ?, ?)
                """,
            UUID.randomUUID(),
            ORGANIZATION_ID,
            USER_ID,
            timestamp(ModelTestFixtures.NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v61UniquenessConstraintIsInstalledAndValidated() {
        Boolean validated = jdbcTemplate.queryForObject(
            """
                select convalidated
                from pg_constraint
                where conname = 'chk_policy_model_key_arrays_unique_v61'
                """,
            Boolean.class
        );
        assertThat(validated).isTrue();
    }

    @Test
    void v59TitleProvenanceCannotMoveBackwardAndLegacyEditBecomesUserOwned() {
        assertThat(jdbcTemplate.queryForObject(
            "select title_source from public.chat_sessions where id=?",
            String.class,
            CHAT_ID
        )).isEqualTo("USER");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "update public.chat_sessions set title_source='AUTO' where id=?",
            CHAT_ID
        )).isInstanceOf(DataIntegrityViolationException.class);

        UUID autoChat = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into public.chat_sessions (
                    id, user_id, organization_id, title, title_source,
                    created_at, updated_at, version,
                    archived_at, archived_by_user_id
                ) values (?, ?, ?, 'Generated title', 'AUTO', ?, ?, 0, null, null)
                """,
            autoChat,
            USER_ID,
            ORGANIZATION_ID,
            timestamp(ModelTestFixtures.NOW),
            timestamp(ModelTestFixtures.NOW)
        );

        jdbcTemplate.update(
            "update public.chat_sessions set title='Manual legacy edit' where id=?",
            autoChat
        );
        assertThat(jdbcTemplate.queryForObject(
            "select title_source from public.chat_sessions where id=?",
            String.class,
            autoChat
        )).isEqualTo("USER");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "update public.chat_sessions set title_source='DEFAULT' where id=?",
            autoChat
        )).isInstanceOf(DataIntegrityViolationException.class);

        UUID defaultChat = UUID.randomUUID();
        jdbcTemplate.update(
            """
                insert into public.chat_sessions (
                    id, user_id, organization_id, title, title_source,
                    created_at, updated_at, version,
                    archived_at, archived_by_user_id
                ) values (?, ?, ?, 'Новый чат', 'DEFAULT', ?, ?, 0, null, null)
                """,
            defaultChat,
            USER_ID,
            ORGANIZATION_ID,
            timestamp(ModelTestFixtures.NOW),
            timestamp(ModelTestFixtures.NOW)
        );

        jdbcTemplate.update(
            "update public.chat_sessions set title='Generated', title_source='AUTO' where id=?",
            defaultChat
        );
        assertThat(jdbcTemplate.queryForObject(
            "select title_source from public.chat_sessions where id=?",
            String.class,
            defaultChat
        )).isEqualTo("AUTO");

        jdbcTemplate.update(
            "update public.chat_sessions set title='User title', title_source='USER' where id=?",
            defaultChat
        );
        assertThat(jdbcTemplate.queryForObject(
            "select title_source from public.chat_sessions where id=?",
            String.class,
            defaultChat
        )).isEqualTo("USER");
    }

    @Test
    void v59CatalogFinancialProvenanceConstraintsAlsoHoldWhenServiceValidationIsBypassed() {
        ModelCatalogEntry base = ModelTestFixtures.configuredEntry();

        ModelCatalogEntry forgedVersion = copyCatalogForDbBypass(
            base,
            "forged\npricing-version",
            base.cacheWriteInputUsdPer1mTokens()
        );
        assertThatThrownBy(() ->
            new ModelCatalogRepository(jdbcTemplate).insert(forgedVersion, "FLAT_TOKEN_V1")
        ).isInstanceOf(DataIntegrityViolationException.class);

        ModelCatalogEntry overflowingSpecializedRate = copyCatalogForDbBypass(
            base,
            "integration-v59-overflow",
            new BigDecimal("999999999999999999.000000000000")
        );
        assertThatThrownBy(() ->
            new ModelCatalogRepository(jdbcTemplate)
                .insert(overflowingSpecializedRate, "FLAT_TOKEN_V1")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v59KnowledgeGenerationHardeningObjectsAreInstalledAndValidated() {
        Integer triggerCount = jdbcTemplate.queryForObject(
            """
                select count(*)
                from pg_trigger
                where tgname = 'trg_generation_insert_guard_v59'
                  and not tgisinternal
                """,
            Integer.class
        );
        Boolean chronologyValidated = jdbcTemplate.queryForObject(
            """
                select convalidated
                from pg_constraint
                where conname = 'chk_generation_retirement_chronology_v59'
                """,
            Boolean.class
        );

        assertThat(triggerCount).isEqualTo(1);
        assertThat(chronologyValidated).isTrue();
    }

    @Test
    void v60PendingReservationCarriesAcrossMonthBoundaryUntilPhysicalOutcomeIsKnown() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        ModelRouteDecisionRepository repository =
            new ModelRouteDecisionRepository(jdbcTemplate);
        BigDecimal reserved = jdbcTemplate.queryForObject(
            "select estimated_max_cost_usd from public.model_route_decisions where id=?",
            BigDecimal.class,
            execution.decisionId()
        );
        assertThat(reserved).isNotNull().isPositive();

        var septemberPending = repository.loadCommittedMonthlyCostSnapshot(
            ORGANIZATION_ID,
            SEPTEMBER_START,
            OCTOBER_START
        );
        assertThat(septemberPending.actualCostUsd()).isZero();
        assertThat(septemberPending.reservedCostUsd())
            .isEqualByComparingTo(reserved);
        assertThat(septemberPending.committedCostUsd())
            .isEqualByComparingTo(reserved);
        assertThat(septemberPending.unknownCommittedCostCount()).isZero();

        Instant physicalStart = SEPTEMBER_START.plusSeconds(1);
        insertStartedAttempt(execution, physicalStart);

        var septemberStarted = repository.loadCommittedMonthlyCostSnapshot(
            ORGANIZATION_ID,
            SEPTEMBER_START,
            OCTOBER_START
        );
        assertThat(septemberStarted.actualCostUsd()).isZero();
        assertThat(septemberStarted.reservedCostUsd())
            .isEqualByComparingTo(reserved);
        assertThat(septemberStarted.unknownCommittedCostCount()).isZero();
    }

    @Test
    void v60TerminalProviderCostBelongsToPhysicalStartMonthNotDecisionMonth() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        Instant physicalStart = SEPTEMBER_START.plusSeconds(1);
        insertStartedAttempt(execution, physicalStart);

        jdbcTemplate.update(
            """
                update public.model_execution_attempts
                set outcome='SUCCEEDED', outcome_certainty='KNOWN_EXECUTED',
                    retry_safety='SAME_TARGET_RETRY_FORBIDDEN',
                    resolved_physical_model='gpt-test', finished_at=?,
                    usage_status='AVAILABLE', input_tokens=100, output_tokens=200,
                    specialized_dimensions_present=false,
                    specialized_dimensions_valid=true,
                    pricing_status='PRICED', cost_usd=0.125,
                    currency='USD', price_book_version='integration-v60',
                    response_observed=true,
                    response_validation_status='ACCEPTED',
                    protocol_failure_code=null
                where execution_plan_id=? and attempt_number=1
                """,
            timestamp(physicalStart.plusSeconds(1)),
            execution.planId()
        );

        assertThat(jdbcTemplate.queryForObject(
            "select response_observed from public.model_execution_attempts "
                + "where execution_plan_id=? and attempt_number=1",
            Boolean.class,
            execution.planId()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "select response_validation_status from public.model_execution_attempts "
                + "where execution_plan_id=? and attempt_number=1",
            String.class,
            execution.planId()
        )).isEqualTo("ACCEPTED");

        ModelRouteDecisionRepository repository =
            new ModelRouteDecisionRepository(jdbcTemplate);
        var august = repository.loadCommittedMonthlyCostSnapshot(
            ORGANIZATION_ID,
            AUGUST_START,
            SEPTEMBER_START
        );
        var september = repository.loadCommittedMonthlyCostSnapshot(
            ORGANIZATION_ID,
            SEPTEMBER_START,
            OCTOBER_START
        );

        assertThat(august.actualCostUsd()).isZero();
        assertThat(august.reservedCostUsd()).isZero();
        assertThat(august.unknownCommittedCostCount()).isZero();

        assertThat(september.actualCostUsd())
            .isEqualByComparingTo("0.125000000000");
        assertThat(september.reservedCostUsd()).isZero();
        assertThat(september.committedCostUsd())
            .isEqualByComparingTo("0.125000000000");
        assertThat(september.unknownCommittedCostCount()).isZero();
    }

    @Test
    void v59MaxRequestCostMakesTheSealedDecisionSinglePhysicalAttemptEvenAfterLatestPolicyChanges() {
        GovernedExecution execution =
            createConfiguredProcessingExecution(true);
        ModelRouteDecisionRepository repository =
            new ModelRouteDecisionRepository(jdbcTemplate);

        assertThat(repository.requiresSinglePhysicalAttempt(
            execution.decisionId()
        )).isTrue();

        Instant started = SEPTEMBER_START.plusSeconds(1);
        insertStartedAttempt(execution, started);
        jdbcTemplate.update(
            """
                insert into public.organization_model_policies (
                    id, organization_id, version, enabled,
                    allow_model_keys, deny_model_keys, default_model_key,
                    max_input_tokens, max_output_tokens,
                    max_request_cost_usd, monthly_budget_usd,
                    budget_enforcement, require_complete_pricing,
                    require_no_training, require_zero_data_retention,
                    created_by_user_id, created_at
                ) values (?, ?, 302, true, '{}'::text[], '{}'::text[], null,
                          null, null, null, null, 'SOFT', false, false, false, ?, ?)
                """,
            UUID.randomUUID(),
            ORGANIZATION_ID,
            USER_ID,
            timestamp(ModelTestFixtures.NOW.plusSeconds(1))
        );

        assertThat(repository.requiresSinglePhysicalAttempt(
            execution.decisionId()
        )).isTrue();

        jdbcTemplate.update(
            """
                update public.model_execution_attempts
                set outcome='FAILED', outcome_certainty='KNOWN_REJECTED',
                    retry_safety='SAME_TARGET_RETRY_ALLOWED',
                    failure_type='RATE_LIMITED', finished_at=?
                where execution_plan_id=? and attempt_number=1
                """,
            timestamp(started.plusSeconds(1)),
            execution.planId()
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                insert into public.model_execution_attempts (
                    id, execution_plan_id, attempt_number, provider_attempt_id,
                    provider_type, provider_configuration_ref,
                    provider_configuration_version,
                    deployment_ref, deployment_version,
                    requested_physical_model,
                    started_at, outcome, outcome_certainty,
                    retry_safety, fallback_safety, created_at
                ) values (
                    ?, ?, 2, ?, 'openai', 'static:openai', 'static-v1',
                    'static:openai:gpt-test', 'static-v1', 'gpt-test', ?,
                    'STARTED', 'AMBIGUOUS', 'SAME_TARGET_RETRY_FORBIDDEN',
                    'FALLBACK_FORBIDDEN', ?
                )
                """,
            UUID.randomUUID(),
            execution.planId(),
            UUID.randomUUID(),
            timestamp(started.plusSeconds(2)),
            timestamp(started.plusSeconds(2))
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private GovernedExecution createConfiguredProcessingExecution() {
        return createConfiguredProcessingExecution(false);
    }

    @Test
    void v100ChangedPolicyBeforeStartedDeniesPhysicalAttempt() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        ModelIntegrationTestSupport.insertAllowAllPolicy(jdbcTemplate, ORGANIZATION_ID, USER_ID,
            302, null, null, databaseNow());
        assertThatThrownBy(() -> insertStartedAttempt(execution, databaseNow()))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V100 current model authorization required");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from public.model_execution_attempts where execution_plan_id=?",
            Long.class, execution.planId())).isZero();
    }

    @Test
    void v100ChangedPolicyAfterStartedDeniesDispatch() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        insertStartedAttempt(execution, databaseNow());
        UUID providerAttempt = providerAttempt(execution);
        ModelIntegrationTestSupport.insertAllowAllPolicy(jdbcTemplate, ORGANIZATION_ID, USER_ID,
            302, null, null, databaseNow());
        assertThatThrownBy(() -> consumeDispatch(execution, providerAttempt))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V100 current model authorization required");
        assertThat(receiptCount(execution)).isZero();
    }

    @Test
    void v100RevokedOrganizationAfterStartedDeniesDispatch() {
        var execution = createConfiguredProcessingExecution();
        insertStartedAttempt(execution, databaseNow());
        UUID providerAttempt = providerAttempt(execution);
        assertThat(jdbcTemplate.update("""
            update public.organizations set enabled=false, auth_version=auth_version+1 where id=?
            """, ORGANIZATION_ID)).isEqualTo(1);
        assertThatThrownBy(() -> consumeDispatch(execution, providerAttempt))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V100 current model authorization required");
        assertThat(receiptCount(execution)).isZero();
    }

    @Test
    void v100ExpiredProcessingLeaseAfterStartedDeniesDispatch() {
        var execution = createConfiguredProcessingExecution();
        insertStartedAttempt(execution, databaseNow());
        UUID providerAttempt = providerAttempt(execution);

        assertThat(jdbcTemplate.update("""
            update public.chat_turns
            set lease_until = least(
                lease_until,
                clock_timestamp() + interval '2 seconds'
            )
            where id = ?
              and state = 'PROCESSING'
              and lease_until > clock_timestamp()
              and updated_at < clock_timestamp()
            """, execution.turnId())).isEqualTo(1);

        await("Valid PROCESSING lease expiration")
            .pollInSameThread()
            .pollInterval(Duration.ofMillis(50))
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject("""
                    select state = 'PROCESSING'
                        and processing_token is not null
                        and provider_call_started_at is not null
                        and lease_until > updated_at
                        and lease_until <= clock_timestamp()
                    from public.chat_turns
                    where id = ?
                    """, Boolean.class, execution.turnId()))
                    .isTrue()
            );

        assertThat(receiptCount(execution)).isZero();

        assertThatThrownBy(() ->
            consumeDispatch(execution, providerAttempt))
            .isInstanceOfSatisfying(
                DataIntegrityViolationException.class,
                failure -> {
                    assertThat(failure).hasMessageContaining(
                        "V100 current model authorization required");

                    assertThat(failure.getMostSpecificCause())
                        .isInstanceOfSatisfying(
                            java.sql.SQLException.class,
                            sql -> assertThat(sql.getSQLState())
                                .isEqualTo("23514")
                        );
                }
            );

        assertThat(receiptCount(execution)).isZero();

        assertThat(jdbcTemplate.queryForObject("""
            select outcome
            from public.model_execution_attempts
            where provider_attempt_id = ?
            """, String.class, providerAttempt))
            .isEqualTo("STARTED");
    }

    @Test
    void v100DispatchReceiptIsDurableOneShotAndImmutable() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        insertStartedAttempt(execution, databaseNow());
        UUID providerAttempt = providerAttempt(execution);
        Instant beforeDispatch = databaseNow();
        assertThat(consumeDispatch(execution, providerAttempt)).isTrue();
        assertThat(receiptCount(execution)).isEqualTo(1L);
        assertThatThrownBy(() -> consumeDispatch(execution, providerAttempt))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        UUID physicalId = jdbcTemplate.queryForObject(
            "select id from public.model_execution_attempts where provider_attempt_id=?",
            UUID.class, providerAttempt);
        assertThat(jdbcTemplate.queryForObject("""
            select isfinite(authorized_at)
                and authorized_at between ? and clock_timestamp()
            from public.model_physical_dispatch_receipts_v100 where attempt_id=?
            """, Boolean.class, timestamp(beforeDispatch), physicalId)).isTrue();
        assertThatThrownBy(() -> jdbcTemplate.update("""
            update public.model_physical_dispatch_receipts_v100
            set authorized_at=clock_timestamp() where attempt_id=?
            """, physicalId)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V100 dispatch receipt is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("""
            delete from public.model_physical_dispatch_receipts_v100 where attempt_id=?
            """, physicalId)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V100 dispatch receipt is immutable");
        assertThat(receiptCount(execution)).isEqualTo(1L);
    }

    @Test
    void v100MissingAdmissionTriggerCannotTurnDefaultIntoValidReceipt() {
        // LIKE copies the actual DEFAULT and CHECK but not triggers or FKs.
        // Never disable the production table's admission guard in a test.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> {
                jdbcTemplate.execute("""
                    create temporary table v100_receipt_without_trigger
                    (like public.model_physical_dispatch_receipts_v100
                        including defaults including constraints)
                    on commit drop
                    """);
                jdbcTemplate.update("""
                    insert into pg_temp.v100_receipt_without_trigger(attempt_id)
                    values (?)
                    """, UUID.randomUUID());
            }))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class, failure ->
                assertThat(failure.getMostSpecificCause())
                    .isInstanceOfSatisfying(java.sql.SQLException.class, sql ->
                        assertThat(sql.getSQLState()).isEqualTo("23514")));
    }

    @Test
    void v100ConcurrentDispatchCannotConsumeSameAttemptTwice() throws Exception {
        GovernedExecution execution = createConfiguredProcessingExecution();
        insertStartedAttempt(execution, databaseNow());
        UUID providerAttempt = providerAttempt(execution);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> call = () -> {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
                try {
                    return consumeDispatch(execution, providerAttempt);
                } catch (org.springframework.dao.DataAccessException failure) {
                    if (failure.getMostSpecificCause() instanceof java.sql.SQLException sql
                        && Set.of("23505", "55P03").contains(sql.getSQLState())) return false;
                    throw failure;
                }
            };
            var a = executor.submit(call);
            var b = executor.submit(call);
            gate.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(receiptCount(execution)).isEqualTo(1L);
    }

    @Test
    void v100NoReceiptAbortReleasesReservationAndCannotBeReplayedAsDispatch() {
        var execution = createConfiguredProcessingExecution();
        insertStartedAttempt(execution, databaseNow());
        UUID attempt = providerAttempt(execution);
        attemptRecorder.abortedBeforeDispatch(attempt);
        attemptRecorder.abortedBeforeDispatch(attempt); // exact replay is read-only
        assertThat(jdbcTemplate.queryForObject("""
            select outcome || ':' || outcome_certainty || ':' || failure_type
            from public.model_execution_attempts where provider_attempt_id=?
            """, String.class, attempt)).isEqualTo("FAILED:KNOWN_NOT_EXECUTED:DISPATCH_ABORTED");
        assertThat(receiptCount(execution)).isZero();
        assertThatThrownBy(() -> consumeDispatch(execution, attempt))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void v100ReceiptPreventsFalseKnownNotExecutedAbort() {
        var execution = createConfiguredProcessingExecution();
        insertStartedAttempt(execution, databaseNow());
        UUID attempt = providerAttempt(execution);
        assertThat(consumeDispatch(execution, attempt)).isTrue();
        assertThatThrownBy(() -> attemptRecorder.abortedBeforeDispatch(attempt))
            .hasStackTraceContaining("V100 abort requires proof of no dispatch receipt");
        assertThat(jdbcTemplate.queryForObject("select outcome from public.model_execution_attempts where provider_attempt_id=?",
            String.class, attempt)).isEqualTo("STARTED");
        assertThat(receiptCount(execution)).isEqualTo(1L);
    }

    @Test
    void v100SettlementWaitsForConcurrentTenantAdmissionInsteadOfLosingResult() throws Exception {
        var execution = createConfiguredProcessingExecution();
        insertStartedAttempt(execution, databaseNow());
        UUID attempt = providerAttempt(execution);
        assertThat(consumeDispatch(execution, attempt)).isTrue();
        var pricing = new ru.safeai.gateway.ai.pricing.PricingResult(
            ru.safeai.gateway.ai.metadata.PricingStatus.PRICED,
            new BigDecimal("0.125"), "USD", "integration-v100", databaseNow());
        var response = ru.safeai.gateway.ai.dto.AiChatResponse.fromProvider(
            "response", "gpt-test", "gpt-test", "message", "request",
            ru.safeai.gateway.ai.metadata.AiResponseStatus.COMPLETED,
            "completed", 100, 200, pricing);
        var tx = new TransactionTemplate(transactionManager);
        var ready = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = tx.execute(status -> {
                assertThat(jdbcTemplate.queryForObject("select public.lock_model_settlement_v100(?)", Boolean.class,
                    attempt)).isTrue();
                var result = pool.submit(() -> {
                    ready.countDown();
                    attemptRecorder.succeeded(attempt, response);
                });
                try {
                    assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                assertThat(result).isNotDone();
                return result;
            });
            Objects.requireNonNull(future).get(15, TimeUnit.SECONDS);
        }
        assertThat(jdbcTemplate.queryForObject("select outcome_certainty from public.model_execution_attempts where provider_attempt_id=?",
            String.class, attempt)).isEqualTo("KNOWN_EXECUTED");
        assertThat(jdbcTemplate.queryForObject("select cost_usd from public.model_execution_attempts where provider_attempt_id=?",
            BigDecimal.class, attempt)).isEqualByComparingTo("0.125");
        attemptRecorder.succeeded(attempt, response);
    }

    @Test
    void v100HardBudgetRechecksActualSpendAfterStarted() {
        var first = createConfiguredProcessingExecution(false, CHAT_ID, new BigDecimal("0.10"), true);
        UUID secondChat = UUID.randomUUID();
        insertChatSession(secondChat, USER_ID, ORGANIZATION_ID);
        var second = createConfiguredProcessingExecution(false, secondChat, new BigDecimal("0.10"), false);
        Instant now = databaseNow();
        insertStartedAttempt(first, now);
        insertStartedAttempt(second, now);
        assertThat(consumeDispatch(first, providerAttempt(first))).isTrue();
        assertThat(jdbcTemplate.update("""
            update public.model_execution_attempts
            set outcome='SUCCEEDED', outcome_certainty='KNOWN_EXECUTED',
                retry_safety='SAME_TARGET_RETRY_FORBIDDEN',
                resolved_physical_model='gpt-test', finished_at=?,
                usage_status='AVAILABLE', input_tokens=100, output_tokens=200,
                specialized_dimensions_present=false, specialized_dimensions_valid=true,
                pricing_status='PRICED', cost_usd=0.125,
                currency='USD', price_book_version='integration-v100',
                response_observed=true, response_validation_status='ACCEPTED',
                protocol_failure_code=null
            where execution_plan_id=? and attempt_number=1
            """, timestamp(now.plusSeconds(1)), first.planId())).isEqualTo(1);
        assertThatThrownBy(() -> consumeDispatch(second, providerAttempt(second)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V100 current hard budget denied");
        assertThat(receiptCount(first)).isEqualTo(1L);
        assertThat(receiptCount(second)).isZero();
    }

    @Test
    void v100OldUtcMonthCannotStartHardBudgetAttempt() {
        var execution = createConfiguredProcessingExecution(false, CHAT_ID, new BigDecimal("0.10"), true);
        Instant previousMonth = databaseNow().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
            .minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThatThrownBy(() -> insertStartedAttempt(execution, previousMonth))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V100 current hard budget evidence required");
    }

    private UUID providerAttempt(GovernedExecution execution) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "select provider_attempt_id from public.model_execution_attempts where execution_plan_id=?",
            UUID.class, execution.planId()));
    }

    private boolean consumeDispatch(GovernedExecution execution, UUID providerAttempt) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "select public.consume_model_dispatch_v100(?, ?)", Boolean.class,
            execution.planId(), providerAttempt));
    }

    private Long receiptCount(GovernedExecution execution) {
        return jdbcTemplate.queryForObject("""
            select count(*) from public.model_physical_dispatch_receipts_v100 r
            join public.model_execution_attempts a on a.id=r.attempt_id
            where a.execution_plan_id=?
            """, Long.class, execution.planId());
    }

    @Test
    void v95StrictQuotaBlocksPhysicalRetryEvenAfterPolicyDisable() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        jdbcTemplate.update("""
            insert into public.organization_ai_quotas
                (organization_id, enabled, monthly_input_token_limit, created_at, updated_at)
            values (?, true, 1000000, current_timestamp, current_timestamp)
            """, ORGANIZATION_ID);
        Instant now = databaseNow();
        insertRouteQuotaForV95(execution, now, false);
        var repository = new ModelRouteDecisionRepository(jdbcTemplate);
        assertThat(repository.requiresSinglePhysicalAttempt(execution.decisionId())).isTrue();
        insertStartedAttempt(execution, now);
        finishRetryableAttempt(execution, now);
        jdbcTemplate.update("update public.organization_ai_quotas set enabled=false where organization_id=?",
            ORGANIZATION_ID);
        assertThat(repository.requiresSinglePhysicalAttempt(execution.decisionId())).isTrue();
        assertThatThrownBy(() -> jdbcTemplate.update("""
            update public.chat_quota_reservations set single_physical_attempt=false where turn_id=?
            """, execution.turnId())).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V95 quota reservation evidence is immutable");
        assertThatThrownBy(() -> insertStartedAttempt(execution, now.plusSeconds(2), 2))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V95 bounded chat quota permits exactly one physical attempt");
    }

    @Test
    void v95ExpiredUserQuotaReservationCannotStartPhysicalExecution() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        jdbcTemplate.update("""
            insert into public.user_ai_quotas
                (user_id, enabled, monthly_output_token_limit, created_at, updated_at)
            values (?, true, 1000000, current_timestamp, current_timestamp)
            """, USER_ID);
        Instant now = databaseNow();
        insertRouteQuotaForV95(execution, now, true);
        assertThatThrownBy(() -> insertStartedAttempt(execution, now))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V95 bounded chat quota requires current route reservation");
    }

    @Test
    void v95UnboundedReservationKeepsExistingSafeRetry() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        Instant now = databaseNow();
        insertRouteQuotaForV95(execution, now, false);
        assertThat(new ModelRouteDecisionRepository(jdbcTemplate)
            .requiresSinglePhysicalAttempt(execution.decisionId())).isFalse();
        insertStartedAttempt(execution, now);
        finishRetryableAttempt(execution, now);
        insertStartedAttempt(execution, now.plusSeconds(2), 2);
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from public.model_execution_attempts where execution_plan_id=?
            """, Long.class, execution.planId())).isEqualTo(2L);
    }

    @Test
    void v95NewlyEnabledQuotaNeedsNewAdmission() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        Instant now = databaseNow();
        insertRouteQuotaForV95(execution, now, false);
        jdbcTemplate.update("""
            insert into public.organization_ai_quotas
                (organization_id, enabled, monthly_cost_limit_usd, created_at, updated_at)
            values (?, true, 1, current_timestamp, current_timestamp)
            """, ORGANIZATION_ID);
        assertThat(new ModelRouteDecisionRepository(jdbcTemplate)
            .requiresSinglePhysicalAttempt(execution.decisionId())).isTrue();
        assertThatThrownBy(() -> insertStartedAttempt(execution, now))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V95 bounded chat quota requires current route reservation");
    }

    @Test
    void v96PolicyReductionInvalidatesUnstartedReservation() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        jdbcTemplate.update("""
            insert into public.organization_ai_quotas
                (organization_id, enabled, monthly_input_token_limit, created_at, updated_at)
            values (?, true, 1000000, current_timestamp, current_timestamp)
            """, ORGANIZATION_ID);
        Instant now = databaseNow();
        insertRouteQuotaForV95(execution, now, false);
        jdbcTemplate.update("""
            update public.organization_ai_quotas set monthly_input_token_limit=0
            where organization_id=?
            """, ORGANIZATION_ID);
        assertThatThrownBy(() -> insertStartedAttempt(execution, now))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V96 current quota admission required");
    }

    @Test
    void v96PolicyChangeAfterStartedStopsPredispatch() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        jdbcTemplate.update("""
            insert into public.user_ai_quotas
                (user_id, enabled, monthly_request_limit, created_at, updated_at)
            values (?, true, 100, current_timestamp, current_timestamp)
            """, USER_ID);
        Instant now = databaseNow();
        insertRouteQuotaForV95(execution, now, false);
        insertStartedAttempt(execution, now);
        UUID attemptId = jdbcTemplate.queryForObject("""
            select provider_attempt_id from public.model_execution_attempts where execution_plan_id=?
            """, UUID.class, execution.planId());
        assertThat(jdbcTemplate.queryForObject(
            "select public.check_quota_dispatch_v96(?, ?)", Boolean.class,
            execution.planId(), attemptId)).isTrue();
        jdbcTemplate.update("update public.user_ai_quotas set monthly_request_limit=0 where user_id=?", USER_ID);
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
            "select public.check_quota_dispatch_v96(?, ?)", Boolean.class, execution.planId(), attemptId))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V96 current quota admission required");
    }

    @Test
    void v96AbsentPolicyCreationWaitsForAdmissionTransaction() throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(8);
        var writerReady = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = transaction.execute(status -> {
                var quota = new ChatQuotaRepository(jdbcTemplate);
                quota.lockOrganizationPolicy(ORGANIZATION_ID);
                quota.lockUserPolicy(USER_ID);
                var future = executor.submit(() -> transaction.executeWithoutResult(writerStatus -> {
                    try {
                        assertThat(jdbcTemplate.queryForObject(
                            "select pg_try_advisory_xact_lock(754962, 1)", Boolean.class)).isFalse();
                    } finally {
                        writerReady.countDown();
                    }
                    jdbcTemplate.update("""
                        insert into public.organization_ai_quotas
                            (organization_id, enabled, monthly_request_limit, created_at, updated_at)
                        values (?, true, 1, current_timestamp, current_timestamp)
                        """, ORGANIZATION_ID);
                    jdbcTemplate.update("""
                        insert into public.user_ai_quotas
                            (user_id, enabled, monthly_request_limit, created_at, updated_at)
                        values (?, true, 1, current_timestamp, current_timestamp)
                        """, USER_ID);
                }));
                try {
                    assertThat(writerReady.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                assertThat(jdbcTemplate.queryForObject("""
                    select count(*) from public.organization_ai_quotas where organization_id=?
                    """, Long.class, ORGANIZATION_ID)).isZero();
                return future;
            });
            Objects.requireNonNull(writer).get(10, TimeUnit.SECONDS);
            assertThat(jdbcTemplate.queryForObject("""
                select count(*) from public.user_ai_quotas where user_id=?
                """, Long.class, USER_ID)).isEqualTo(1L);
        }
    }

    @Test
    void v96UnboundedRouteCannotUseExpiredReservation() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        Instant now = databaseNow();
        insertRouteQuotaForV95(execution, now, true);
        assertThatThrownBy(() -> insertStartedAttempt(execution, now))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("V96 current quota admission required");
    }

    @Test
    void v96ConcurrentPhysicalStartsCommitExactlyOnce() throws Exception {
        GovernedExecution execution = createConfiguredProcessingExecution();
        jdbcTemplate.update("""
            insert into public.organization_ai_quotas
                (organization_id, enabled, monthly_input_token_limit, created_at, updated_at)
            values (?, true, 1000000, current_timestamp, current_timestamp)
            """, ORGANIZATION_ID);
        Instant now = databaseNow();
        insertRouteQuotaForV95(execution, now, false);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(8);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> launch = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
            try {
                transaction.executeWithoutResult(status -> insertStartedAttempt(execution, now));
                return true;
            } catch (UncategorizedSQLException denied) {
                // V50 сериализует номера попыток и отклоняет повтор с SQLSTATE P0001.
                var sql = denied.getSQLException();
                if (sql != null && "P0001".equals(sql.getSQLState())
                    && sql.getMessage().contains("V50 execution attempts must be sequential")) {
                    return false;
                }
                throw denied;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(launch);
            var second = executor.submit(launch);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
            assertThat(jdbcTemplate.queryForList("""
                select attempt_number from public.model_execution_attempts where execution_plan_id=?
                """, Integer.class, execution.planId())).containsExactly(1);
        }
    }

    @Test
    void v96CalendarDoesNotDependOnSessionTimezone() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL TIME ZONE 'Pacific/Auckland'");
            insertRouteQuotaForV95(execution, databaseNow(), false);
            assertThat(jdbcTemplate.queryForObject("""
                select period_zone_v96 from public.chat_quota_reservations where turn_id=?
                """, String.class, execution.turnId())).isEqualTo("UTC");
        });
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "select clock_timestamp()", java.sql.Timestamp.class)).toInstant();
    }

    private void insertRouteQuotaForV95(GovernedExecution execution, Instant now, boolean expired) {
        LocalDate month = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        if (expired) month = month.minusMonths(1);
        Instant begin = month.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant created = expired ? begin.plusSeconds(1) : now;
        assertThat(jdbcTemplate.update("""
                insert into public.chat_quota_reservations (
                    turn_id, organization_id, user_id, period_start, state,
                    model_route_decision_id, reservation_version, cost_verifiable,
                    reserved_input_tokens, reserved_output_tokens, reserved_cost_usd,
                    created_at, updated_at, physical_period_start, physical_period_end)
                select ?, ?, ?, ?, 'RESERVED', id, 2, pricing_complete,
                    estimated_input_tokens, estimated_output_tokens, coalesce(estimated_max_cost_usd, 0),
                    ?, ?, ?, ? from public.model_route_decisions where id=?
                """, execution.turnId(), ORGANIZATION_ID, USER_ID, java.sql.Date.valueOf(month),
            timestamp(created), timestamp(created), timestamp(begin), timestamp(end),
            execution.decisionId())).isEqualTo(1);
    }

    private void finishRetryableAttempt(GovernedExecution execution, Instant started) {
        assertThat(jdbcTemplate.update("""
            update public.model_execution_attempts
            set outcome='FAILED', outcome_certainty='KNOWN_REJECTED',
                retry_safety='SAME_TARGET_RETRY_ALLOWED', failure_type='RATE_LIMITED', finished_at=?
            where execution_plan_id=? and attempt_number=1
            """, timestamp(started.plusSeconds(1)), execution.planId())).isEqualTo(1);
    }

    @Test
    void releasedProviderRejectionRetainsUnknownTokenAndMoneyLiability() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        insertQuotaReservation(execution.turnId(), ORGANIZATION_ID, USER_ID);
        Instant started = SEPTEMBER_START.plusSeconds(1);
        insertStartedAttempt(execution, started);
        finishRejectedAttempt(execution, started, "KNOWN_REJECTED");

        var quota = new ChatQuotaRepository(jdbcTemplate);
        quota.releaseFailure(execution.turnId(), started.plusSeconds(2));
        LocalDate reservationMonth = LocalDate.of(2026, 6, 1);
        var organization = quota.organizationConsumption(ORGANIZATION_ID, reservationMonth);
        var user = quota.userConsumption(USER_ID, reservationMonth);
        assertThat(organization).isEqualTo(user);
        assertThat(organization.requests()).isZero();
        assertThat(organization.usageUnverifiable()).isTrue();
        assertThat(organization.costUnverifiable()).isTrue();
        var reportRepository = new UsagePhysicalSpendRepository(new UsageJdbcClients(
            Objects.requireNonNull(jdbcTemplate.getDataSource()),
            new UsageProperties(null, null, null, null, null, null, null, null, null)));
        var report = reportRepository.findCoverage(SEPTEMBER_START, OCTOBER_START, ORGANIZATION_ID);
        assertThat(report.knownRejectedAttempts()).isEqualTo(1);
        assertThat(report.usageComplete()).isFalse();
        assertThat(report.costComplete()).isFalse();
        assertThat(report.knownCostUsd()).isZero();
        assertThat(quota.organizationConsumption(OTHER_ORGANIZATION_ID, reservationMonth)
            .usageUnverifiable()).isFalse();
        assertThat(quota.userConsumption(OTHER_USER_ID, reservationMonth)
            .costUnverifiable()).isFalse();
        assertThat(quota.organizationConsumption(ORGANIZATION_ID, reservationMonth.plusMonths(1))
            .usageUnverifiable()).isFalse();
    }

    @Test
    void provedNonExecutionDoesNotInventUnknownLiabilityAfterRelease() {
        GovernedExecution execution = createConfiguredProcessingExecution();
        insertQuotaReservation(execution.turnId(), ORGANIZATION_ID, USER_ID);
        Instant started = SEPTEMBER_START.plusSeconds(1);
        insertStartedAttempt(execution, started);
        finishRejectedAttempt(execution, started, "KNOWN_NOT_EXECUTED");

        var quota = new ChatQuotaRepository(jdbcTemplate);
        quota.releaseFailure(execution.turnId(), started.plusSeconds(2));
        LocalDate reservationMonth = LocalDate.of(2026, 6, 1);
        var organization = quota.organizationConsumption(ORGANIZATION_ID, reservationMonth);
        assertThat(organization).isEqualTo(quota.userConsumption(USER_ID, reservationMonth));
        assertThat(organization.requests()).isZero();
        assertThat(organization.inputTokens()).isZero();
        assertThat(organization.outputTokens()).isZero();
        assertThat(organization.costUsd()).isZero();
        assertThat(organization.usageUnverifiable()).isFalse();
        assertThat(organization.costUnverifiable()).isFalse();
    }

    private void finishRejectedAttempt(GovernedExecution execution, Instant started, String certainty) {
        assertThat(jdbcTemplate.update("""
            update public.model_execution_attempts
            set outcome='FAILED', outcome_certainty=?,
                retry_safety='SAME_TARGET_RETRY_FORBIDDEN',
                failure_type='RATE_LIMITED', finished_at=?
            where execution_plan_id=? and attempt_number=1
            """, certainty, timestamp(started.plusSeconds(1)), execution.planId())).isEqualTo(1);
    }

    private GovernedExecution createConfiguredProcessingExecution(
        boolean boundedByRequestCost
    ) {
        return createConfiguredProcessingExecution(boundedByRequestCost, CHAT_ID, null, true);
    }

    private GovernedExecution createConfiguredProcessingExecution(
        boolean boundedByRequestCost, UUID chatId, BigDecimal hardBudget, boolean installPolicy
    ) {
        String modelKey =
            "openai:gpt-test:" + UUID.randomUUID();
        ModelCatalogEntry template = ModelTestFixtures.configuredEntry();
        ModelCatalogEntry entry = new ModelCatalogEntry(
            UUID.randomUUID(),
            modelKey,
            1,
            "openai",
            "gpt-test",
            "GPT Test",
            template.lifecycle(),
            template.maxInputTokens(),
            template.maxOutputTokens(),
            template.capabilities(),
            template.inputModalities(),
            template.outputModalities(),
            template.retentionStatus(),
            template.retentionDays(),
            template.trainingUseStatus(),
            template.pricingStatus(),
            template.pricingComplete(),
            template.inputUsdPer1mTokens(),
            template.cachedInputUsdPer1mTokens(),
            template.cacheWriteInputUsdPer1mTokens(),
            template.outputUsdPer1mTokens(),
            template.extraPricingJson(),
            template.pricingVersion(),
            ModelTestFixtures.NOW.minusSeconds(120),
            template.source(),
            USER_ID,
            ModelTestFixtures.NOW.minusSeconds(180),
            ModelPricingScheme.FLAT_TOKEN_V1
        );

        ModelCatalogRepository catalogRepository =
            new ModelCatalogRepository(jdbcTemplate);
        OrganizationModelPolicyRepository policyRepository =
            new OrganizationModelPolicyRepository(jdbcTemplate);
        ModelRouteDecisionRepository decisionRepository =
            new ModelRouteDecisionRepository(jdbcTemplate);

        catalogRepository.insert(entry, "FLAT_TOKEN_V1");

        if (installPolicy && hardBudget != null) {
            assertThat(jdbcTemplate.update("""
                    insert into public.organization_model_policies (
                        id, organization_id, version, enabled, allow_model_keys, deny_model_keys,
                        monthly_budget_usd, budget_enforcement, require_complete_pricing,
                        require_no_training, require_zero_data_retention, created_by_user_id, created_at
                    ) values (?, ?, 301, true, '{}'::text[], '{}'::text[], ?, 'HARD', true,
                        false, false, ?, ?)
                    """, UUID.randomUUID(), ORGANIZATION_ID, hardBudget, USER_ID,
                timestamp(ModelTestFixtures.NOW.minusSeconds(30)))).isEqualTo(1);
        } else if (installPolicy) {
            ModelIntegrationTestSupport.insertAllowAllPolicy(
                jdbcTemplate,
                ORGANIZATION_ID,
                USER_ID,
                301,
                boundedByRequestCost
                    ? new BigDecimal("100.000000000000")
                    : null,
                null,
                ModelTestFixtures.NOW.minusSeconds(30)
            );
        }

        RuntimeModelStatusService runtime =
            mock(RuntimeModelStatusService.class);
        when(runtime.current()).thenReturn(
            ModelTestFixtures.configuredRuntime()
        );
        ModelRoutingService routing =
            ModelIntegrationTestSupport.routingService(
                jdbcTemplate,
                catalogRepository,
                policyRepository,
                decisionRepository,
                runtime,
                mock(AuditEventService.class),
                ModelTestFixtures.CLOCK
            );

        UUID turnId = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID messageId = insertUserMessage(
            chatId,
            ORGANIZATION_ID,
            clientRequestId,
            "hello",
            ModelTestFixtures.NOW.minusSeconds(20)
        );
        SafeAiUserPrincipal principal =
            SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

        UUID[] decisionHolder = new UUID[1];
        UUID planId = UUID.randomUUID();

        new TransactionTemplate(transactionManager)
            .executeWithoutResult(tx -> {
                decisionHolder[0] = routing.decide(
                    new ModelRouteRequest(
                        ORGANIZATION_ID,
                        USER_ID,
                        chatId,
                        turnId,
                        clientRequestId,
                        ModelTestFixtures.REQUEST_HASH,
                        modelKey,
                        "hello",
                        List.of(),
                        Set.of(),
                        0L
                    ),
                    principal
                ).decisionId();

                // V67 validates an active PROCESSING lease against the
                // PostgreSQL wall clock. The historical governance and
                // physical accounting instants must remain unchanged.
                Instant leaseNow = java.util.Objects.requireNonNull(
                    jdbcTemplate.queryForObject(
                        "select clock_timestamp()",
                        java.sql.Timestamp.class
                    ),
                    "PostgreSQL clock must be available"
                ).toInstant();

                jdbcTemplate.update(
                    """
                        insert into public.chat_turns (
                            id, session_id, organization_id, user_id,
                            client_request_id, request_content_hash,
                            provider_operation_id, user_message_id,
                            state, processing_token, lease_until,
                            provider_call_started_at,
                            provider, requested_model,
                            knowledge_mode, knowledge_base_id,
                            outcome_ambiguous,
                            model_route_decision_id,
                            created_at, updated_at, version
                        ) values (
                            ?, ?, ?, ?, ?, ?, ?, ?,
                            'PROCESSING', ?, ?, ?,
                            'openai', 'gpt-test', 'GENERAL', null,
                            false, ?, ?, ?, 0
                        )
                        """,
                    turnId,
                    chatId,
                    ORGANIZATION_ID,
                    USER_ID,
                    clientRequestId,
                    ModelTestFixtures.REQUEST_HASH,
                    operationId,
                    messageId,
                    UUID.randomUUID(),
                    timestamp(leaseNow.plusSeconds(180)),
                    timestamp(ModelTestFixtures.NOW),
                    decisionHolder[0],
                    timestamp(ModelTestFixtures.NOW.minusSeconds(10)),
                    timestamp(ModelTestFixtures.NOW.minusSeconds(10))
                );

                jdbcTemplate.update(
                    """
                        insert into public.model_route_reserved_requests (
                            model_route_decision_id, chat_turn_id,
                            provider_operation_id,
                            base_request_sha256, created_at
                        ) values (?, ?, ?, ?, ?)
                        """,
                    decisionHolder[0],
                    turnId,
                    operationId,
                    ModelIntegrationTestSupport.BASE_REQUEST_SHA256,
                    timestamp(ModelTestFixtures.NOW)
                );

                ModelIntegrationTestSupport.insertGeneralPreparedBindingV64(
                    jdbcTemplate,
                    decisionHolder[0],
                    turnId,
                    operationId,
                    ModelIntegrationTestSupport.BASE_REQUEST_SHA256,
                    ModelIntegrationTestSupport.PREPARED_REQUEST_SHA256,
                    ModelTestFixtures.NOW
                );

                jdbcTemplate.update(
                    """
                        insert into public.model_execution_plans (
                            id, provider_operation_id, chat_turn_id,
                            organization_id, model_route_decision_id,
                            requested_model, created_at
                        ) values (?, ?, ?, ?, ?, 'gpt-test', ?)
                        """,
                    planId,
                    operationId,
                    turnId,
                    ORGANIZATION_ID,
                    decisionHolder[0],
                    timestamp(ModelTestFixtures.NOW)
                );
            });

        return new GovernedExecution(
            decisionHolder[0],
            turnId,
            planId,
            operationId
        );
    }

    private void insertStartedAttempt(
        GovernedExecution execution,
        Instant startedAt
    ) {
        insertStartedAttempt(execution, startedAt, 1);
    }

    private void insertStartedAttempt(GovernedExecution execution, Instant startedAt, int attemptNumber) {
        jdbcTemplate.update(
            """
                insert into public.model_execution_attempts (
                    id, execution_plan_id, attempt_number, provider_attempt_id,
                    provider_type, provider_configuration_ref,
                    provider_configuration_version,
                    deployment_ref, deployment_version,
                    requested_physical_model,
                    started_at, outcome, outcome_certainty,
                    retry_safety, fallback_safety, created_at
                ) values (
                    ?, ?, ?, ?, 'openai', 'static:openai', 'static-v1',
                    'static:openai:gpt-test', 'static-v1', 'gpt-test', ?,
                    'STARTED', 'AMBIGUOUS', 'SAME_TARGET_RETRY_FORBIDDEN',
                    'FALLBACK_FORBIDDEN', ?
                )
                """,
            UUID.randomUUID(),
            execution.planId(),
            attemptNumber,
            UUID.randomUUID(),
            timestamp(startedAt),
            timestamp(startedAt)
        );
    }

    private ModelCatalogEntry copyCatalogForDbBypass(
        ModelCatalogEntry base,
        String pricingVersion,
        BigDecimal cacheWriteInputPrice
    ) {
        return new ModelCatalogEntry(
            UUID.randomUUID(),
            "integration:v59:" + UUID.randomUUID(),
            1,
            base.provider(),
            base.providerModelId(),
            base.displayName(),
            base.lifecycle(),
            Integer.MAX_VALUE,
            1,
            base.capabilities(),
            base.inputModalities(),
            base.outputModalities(),
            base.retentionStatus(),
            base.retentionDays(),
            base.trainingUseStatus(),
            base.pricingStatus(),
            base.pricingComplete(),
            base.inputUsdPer1mTokens(),
            base.cachedInputUsdPer1mTokens(),
            cacheWriteInputPrice,
            base.outputUsdPer1mTokens(),
            base.extraPricingJson(),
            pricingVersion,
            ModelTestFixtures.NOW.minusSeconds(10),
            base.source(),
            USER_ID,
            ModelTestFixtures.NOW.minusSeconds(20),
            ModelPricingScheme.FLAT_TOKEN_V1
        );
    }

    private record GovernedExecution(
        UUID decisionId,
        UUID turnId,
        UUID planId,
        UUID operationId
    ) {
    }
}
