package ru.safeai.gateway.model.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.integration.AbstractChatPostgresIntegrationTest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        Instant started = SEPTEMBER_START.plusSeconds(1);
        insertStartedAttempt(execution, started);
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

    private GovernedExecution createConfiguredProcessingExecution(
            boolean boundedByRequestCost
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
                CHAT_ID,
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
                                    CHAT_ID,
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
                            CHAT_ID,
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
                    ?, ?, 1, ?, 'openai', 'static:openai', 'static-v1',
                    'static:openai:gpt-test', 'static-v1', 'gpt-test', ?,
                    'STARTED', 'AMBIGUOUS', 'SAME_TARGET_RETRY_FORBIDDEN',
                    'FALLBACK_FORBIDDEN', ?
                )
                """,
                UUID.randomUUID(),
                execution.planId(),
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
