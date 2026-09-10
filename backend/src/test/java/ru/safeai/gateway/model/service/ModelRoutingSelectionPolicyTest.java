package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingSelectionPolicyTest {

    private static final Instant NOW =
            Instant.parse("2026-09-09T17:00:00Z");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            );

    private static final UUID CHAT_ID =
            UUID.fromString(
                    "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            );

    private static final UUID PLANNED_TURN_ID =
            UUID.fromString(
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
            );

    private static final UUID CLIENT_REQUEST_ID =
            UUID.fromString(
                    "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
            );

    private static final UUID CREATED_BY_USER_ID =
            UUID.fromString(
                    "ffffffff-ffff-4fff-8fff-ffffffffffff"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "12121212-1212-4212-8212-121212121212"
            );

    private final RuntimeModelStatusResponse runtime =
            new RuntimeModelStatusResponse(
                    "openai",
                    "gpt-x",
                    true,
                    "SINGLE_PROVIDER_STATIC",
                    64_000,
                    8_192,
                    false,
                    false,
                    false,
                    "NOT_DECLARED",
                    "NOT_PROBED",
                    "CONFIGURED",
                    new BigDecimal("5"),
                    new BigDecimal("15"),
                    "openai-2026-09"
            );

    @Test
    void explicitRequestUsesEffectiveCatalogSnapshotAtDecisionInstant() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelCatalogEntry entry =
                entry("openai:gpt-test");

        when(repository.findEffective(
                "openai:gpt-test",
                NOW
        )).thenReturn(Optional.of(entry));

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(
                                        " OpenAI:GPT-Test ",
                                        Set.of()
                                ),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertSame(
                entry,
                selection.entry()
        );
        assertEquals(
                "openai:gpt-test",
                selection.modelKey()
        );
        assertEquals(
                ModelRouteReason.REQUESTED_MODEL,
                selection.allowedReason()
        );
        assertNull(
                selection.denialReason()
        );
    }

    @Test
    void missingExplicitModelFailsClosed() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        when(repository.findEffective(
                "openai:missing",
                NOW
        )).thenReturn(Optional.empty());

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(
                                        "openai:missing",
                                        Set.of()
                                ),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertEquals(
                ModelRouteReason.MODEL_NOT_FOUND,
                selection.denialReason()
        );
        assertEquals(
                "openai:missing",
                selection.modelKey()
        );
        assertNull(
                selection.allowedReason()
        );
        assertNull(
                selection.entry()
        );
    }

    @Test
    void policyDefaultIsUsedWhenRequestDoesNotSelectModel() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        OrganizationModelPolicy tenantPolicy =
                policy(
                        Set.of("openai:gpt-test"),
                        Set.of(),
                        "openai:gpt-test",
                        false,
                        false
                );

        ModelCatalogEntry entry =
                entry("openai:gpt-test");

        when(repository.findEffective(
                "openai:gpt-test",
                NOW
        )).thenReturn(Optional.of(entry));

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                tenantPolicy,
                                true,
                                NOW
                        );

        assertSame(
                entry,
                selection.entry()
        );
        assertEquals(
                "openai:gpt-test",
                selection.modelKey()
        );
        assertEquals(
                ModelRouteReason.POLICY_DEFAULT,
                selection.allowedReason()
        );
        assertNull(
                selection.denialReason()
        );
    }

    @Test
    void singleExecutableRuntimeCandidateIsSelected() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelCatalogEntry entry =
                entry("openai:gpt-main");

        when(repository.findEffectiveByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(List.of(entry));

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertSame(
                entry,
                selection.entry()
        );
        assertEquals(
                "openai:gpt-main",
                selection.modelKey()
        );
        assertEquals(
                ModelRouteReason.RUNTIME_ONLY_MATCH,
                selection.allowedReason()
        );
        assertNull(
                selection.denialReason()
        );
    }

    @Test
    void ambiguousRuntimeMappingFailsClosedInsteadOfAlphabeticalSelection() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        when(repository.findEffectiveByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(
                List.of(
                        entry("openai:gpt-main"),
                        entry("openai:gpt-finance")
                )
        );

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertEquals(
                ModelRouteReason.AMBIGUOUS_RUNTIME_MAPPING,
                selection.denialReason()
        );
        assertNull(
                selection.allowedReason()
        );
        assertNull(
                selection.entry()
        );
        assertNull(
                selection.modelKey()
        );
        assertEquals(
                "openai",
                selection.selectedProvider()
        );
        assertEquals(
                "gpt-x",
                selection.selectedProviderModelId()
        );
    }

    @Test
    void noEffectiveCatalogNeverFallsBackToPhysicalRuntime() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        when(repository.findEffectiveByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(List.of());

        when(repository.hasEffectiveHistoryByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(false);

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertEquals(
                ModelRouteReason.MODEL_NOT_FOUND,
                selection.denialReason()
        );
        assertNull(
                selection.allowedReason()
        );
        assertNull(
                selection.entry()
        );
        assertNull(
                selection.modelKey()
        );

        verify(repository)
                .hasEffectiveHistoryByRuntime(
                        "openai",
                        "gpt-x",
                        NOW
                );
    }

    @Test
    void staleEffectiveHistoryReportsRuntimeMismatchWithoutFallback() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        when(repository.findEffectiveByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(List.of());

        when(repository.hasEffectiveHistoryByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(true);

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertEquals(
                ModelRouteReason.RUNTIME_MISMATCH,
                selection.denialReason()
        );
        assertEquals(
                "runtime:openai:gpt-x",
                selection.modelKey()
        );
        assertNull(
                selection.allowedReason()
        );
        assertNull(
                selection.entry()
        );
        assertEquals(
                "openai",
                selection.selectedProvider()
        );
        assertEquals(
                "gpt-x",
                selection.selectedProviderModelId()
        );
    }

    @Test
    void policyThatFiltersEveryRuntimeCandidateReportsModelNotAllowed() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        when(repository.findEffectiveByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(
                List.of(
                        entry("openai:gpt-main"),
                        entry("openai:gpt-finance")
                )
        );

        OrganizationModelPolicy tenantPolicy =
                policy(
                        Set.of("openai:another-model"),
                        Set.of(),
                        null,
                        false,
                        false
                );

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                tenantPolicy,
                                true,
                                NOW
                        );

        assertEquals(
                ModelRouteReason.MODEL_NOT_ALLOWED,
                selection.denialReason()
        );
        assertNull(
                selection.allowedReason()
        );
        assertNull(
                selection.entry()
        );
    }

    @Test
    void multipleNonExecutableRuntimeCandidatesReportModelDisabledNotAmbiguity() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        when(repository.findEffectiveByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(
                List.of(
                        entry(
                                "openai:gpt-main",
                                ModelLifecycle.DISABLED,
                                Set.of()
                        ),
                        entry(
                                "openai:gpt-finance",
                                ModelLifecycle.RETIRED,
                                Set.of()
                        )
                )
        );

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertEquals(
                ModelRouteReason.MODEL_DISABLED,
                selection.denialReason()
        );
        assertNull(
                selection.allowedReason()
        );
        assertNull(
                selection.entry()
        );
        assertNull(
                selection.modelKey()
        );
        assertEquals(
                "openai",
                selection.selectedProvider()
        );
        assertEquals(
                "gpt-x",
                selection.selectedProviderModelId()
        );
    }

    @Test
    void singleDisabledRuntimeCandidateReportsItsLogicalKey() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelCatalogEntry disabled =
                entry(
                        "openai:gpt-disabled",
                        ModelLifecycle.DISABLED,
                        Set.of()
                );

        when(repository.findEffectiveByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(List.of(disabled));

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertEquals(
                ModelRouteReason.MODEL_DISABLED,
                selection.denialReason()
        );
        assertEquals(
                "openai:gpt-disabled",
                selection.modelKey()
        );
        assertNull(
                selection.allowedReason()
        );
        assertNull(
                selection.entry()
        );
    }

    @Test
    void denyListTakesPrecedenceOverAllowList() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelRoutingSelectionPolicy selectionPolicy =
                new ModelRoutingSelectionPolicy(repository);

        OrganizationModelPolicy tenantPolicy =
                policy(
                        Set.of("openai:gpt-test"),
                        Set.of("openai:gpt-test"),
                        null,
                        false,
                        false
                );

        ModelRouteReason denial =
                selectionPolicy.validateCatalogAndPolicy(
                        entry("openai:gpt-test"),
                        "openai:gpt-test",
                        runtime,
                        tenantPolicy,
                        true,
                        Set.of()
                );

        assertEquals(
                ModelRouteReason.MODEL_DENIED,
                denial
        );
    }

    @Test
    void requiredCapabilityMustExistInCatalogAndRuntime() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelRoutingSelectionPolicy selectionPolicy =
                new ModelRoutingSelectionPolicy(repository);

        ModelCatalogEntry entry =
                entry(
                        "openai:gpt-tools",
                        ModelLifecycle.ACTIVE,
                        Set.of(ModelCapability.TOOLS)
                );

        ModelRouteReason denial =
                selectionPolicy.validateCatalogAndPolicy(
                        entry,
                        entry.modelKey(),
                        runtime,
                        null,
                        false,
                        Set.of(ModelCapability.TOOLS)
                );

        /*
         * Catalog declares TOOLS, but this physical Runtime explicitly does not.
         * Capability governance therefore fails closed.
         */
        assertEquals(
                ModelRouteReason.CAPABILITY_UNSUPPORTED,
                denial
        );
    }

    @Test
    void unsupportedExecutionCapabilityFailsClosedEvenBeforeProviderExecution() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelRoutingSelectionPolicy selectionPolicy =
                new ModelRoutingSelectionPolicy(repository);

        ModelCatalogEntry visionEntry =
                entry(
                        "openai:gpt-vision",
                        ModelLifecycle.ACTIVE,
                        Set.of(ModelCapability.VISION)
                );

        ModelRouteReason denial =
                selectionPolicy.validateCatalogAndPolicy(
                        visionEntry,
                        visionEntry.modelKey(),
                        runtime,
                        null,
                        false,
                        Set.of(ModelCapability.VISION)
                );

        assertEquals(
                ModelRouteReason.CAPABILITY_UNSUPPORTED,
                denial
        );
    }

    @Test
    void noTrainingRequirementFailsClosedOnUnknownDeclaration() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelRoutingSelectionPolicy selectionPolicy =
                new ModelRoutingSelectionPolicy(repository);

        OrganizationModelPolicy tenantPolicy =
                policy(
                        Set.of(),
                        Set.of(),
                        null,
                        true,
                        false
                );

        ModelRouteReason denial =
                selectionPolicy.validateCatalogAndPolicy(
                        entry("openai:gpt-test"),
                        "openai:gpt-test",
                        runtime,
                        tenantPolicy,
                        true,
                        Set.of()
                );

        assertEquals(
                ModelRouteReason.TRAINING_POLICY_UNSATISFIED,
                denial
        );
    }

    @Test
    void zeroRetentionRequirementFailsClosedOnUnknownDeclaration() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelRoutingSelectionPolicy selectionPolicy =
                new ModelRoutingSelectionPolicy(repository);

        OrganizationModelPolicy tenantPolicy =
                policy(
                        Set.of(),
                        Set.of(),
                        null,
                        false,
                        true
                );

        ModelRouteReason denial =
                selectionPolicy.validateCatalogAndPolicy(
                        entry("openai:gpt-test"),
                        "openai:gpt-test",
                        runtime,
                        tenantPolicy,
                        true,
                        Set.of()
                );

        assertEquals(
                ModelRouteReason.RETENTION_POLICY_UNSATISFIED,
                denial
        );
    }

    @Test
    void deprecatedCatalogEntryRemainsRouteEligible() {
        ModelCatalogRepository repository =
                mock(ModelCatalogRepository.class);

        ModelCatalogEntry deprecated =
                entry(
                        "openai:gpt-deprecated",
                        ModelLifecycle.DEPRECATED,
                        Set.of()
                );

        when(repository.findEffectiveByRuntime(
                "openai",
                "gpt-x",
                NOW
        )).thenReturn(List.of(deprecated));

        ModelRoutingSelectionPolicy.Selection selection =
                new ModelRoutingSelectionPolicy(repository)
                        .selectCatalog(
                                request(),
                                runtime,
                                null,
                                false,
                                NOW
                        );

        assertSame(
                deprecated,
                selection.entry()
        );
        assertEquals(
                ModelRouteReason.RUNTIME_ONLY_MATCH,
                selection.allowedReason()
        );
        assertNull(
                selection.denialReason()
        );
    }

    private static OrganizationModelPolicy policy(
            Set<String> allow,
            Set<String> deny,
            String defaultModelKey,
            boolean requireNoTraining,
            boolean requireZeroDataRetention
    ) {
        return new OrganizationModelPolicy(
                POLICY_ID,
                ORGANIZATION_ID,
                1,
                true,
                allow,
                deny,
                defaultModelKey,
                null,
                null,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                requireNoTraining,
                requireZeroDataRetention,
                USER_ID,
                NOW.minusSeconds(60)
        );
    }

    private static ModelRouteRequest request() {
        return request(
                null,
                Set.of()
        );
    }

    private static ModelRouteRequest request(
            String requestedModelKey,
            Set<ModelCapability> requiredCapabilities
    ) {
        return new ModelRouteRequest(
                ORGANIZATION_ID,
                USER_ID,
                CHAT_ID,
                PLANNED_TURN_ID,
                CLIENT_REQUEST_ID,
                "0".repeat(64),
                requestedModelKey,
                "preview",
                List.of(),
                requiredCapabilities,
                0L
        );
    }

    private static ModelCatalogEntry entry(
            String modelKey
    ) {
        return entry(
                modelKey,
                ModelLifecycle.ACTIVE,
                Set.of()
        );
    }

    private static ModelCatalogEntry entry(
            String modelKey,
            ModelLifecycle lifecycle,
            Set<ModelCapability> capabilities
    ) {
        Set<ModelModality> inputModalities =
                capabilities.contains(ModelCapability.VISION)
                        ? Set.of(
                                ModelModality.TEXT,
                                ModelModality.IMAGE
                        )
                        : Set.of(ModelModality.TEXT);

        return new ModelCatalogEntry(
                stableUuid(modelKey),
                modelKey,
                1,
                "openai",
                "gpt-x",
                modelKey,
                lifecycle,
                64_000,
                8_192,
                capabilities,
                inputModalities,
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.CONFIGURED,
                true,
                new BigDecimal("5"),
                null,
                new BigDecimal("10"),
                new BigDecimal("15"),
                "{}",
                "openai-2026-09",
                NOW.minusSeconds(3600),
                ModelCatalogSource.MANUAL,
                CREATED_BY_USER_ID,
                NOW.minusSeconds(7200)
        );
    }

    private static UUID stableUuid(
            String value
    ) {
        return UUID.nameUUIDFromBytes(
                value.getBytes(StandardCharsets.UTF_8)
        );
    }
}

