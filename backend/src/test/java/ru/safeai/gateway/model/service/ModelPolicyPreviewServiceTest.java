package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.model.config.ModelRoutingEnvelopeProperties;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelPolicyPreviewServiceTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final Instant NOW =
            Instant.parse("2026-09-09T17:00:00Z");

    @Test
    void ambiguousAutomaticRuntimeMappingFailsClosedButExplicitCandidatesRemainVisible() {
        Fixture fixture = fixture(List.of(
                entry("openai:gpt-main", "5", "15"),
                entry("openai:gpt-finance", "5", "15")
        ));

        ModelPolicyPreviewResponse preview = fixture.service.preview(
                ORGANIZATION_ID,
                request(null),
                fixture.user
        );

        assertEquals(ModelRouteOutcome.DENIED, preview.automaticOutcome());
        assertEquals(
                ModelRouteReason.AMBIGUOUS_RUNTIME_MAPPING,
                preview.automaticReason()
        );
        assertEquals(2, preview.executableModelCount());
        assertFalse(preview.wouldLockOutOrganization());
        assertEquals(2, preview.models().size());
    }

    @Test
    void maxRequestCostDenialIsReflectedByAutomaticPreviewToo() {
        Fixture fixture = fixture(List.of(
                entry("openai:gpt-main", "5", "15")
        ));

        ModelPolicyPreviewResponse preview = fixture.service.preview(
                ORGANIZATION_ID,
                request(new BigDecimal("0.000001")),
                fixture.user
        );

        assertEquals(ModelRouteOutcome.DENIED, preview.automaticOutcome());
        assertEquals(
                ModelRouteReason.REQUEST_COST_LIMIT_EXCEEDED,
                preview.automaticReason()
        );
        assertEquals(0, preview.executableModelCount());
        assertTrue(preview.wouldLockOutOrganization());
        assertEquals(
                ModelRouteReason.REQUEST_COST_LIMIT_EXCEEDED,
                preview.models().getFirst().reason()
        );
    }

    @Test
    void maxEnvelopeAboveCapDoesNotCreateFalseLockoutWhenMinimumRequestFits() {
        Fixture fixture = fixture(List.of(
                entry("openai:gpt-main", "5", "15")
        ));

        ModelPolicyPreviewResponse preview = fixture.service.preview(
                ORGANIZATION_ID,
                request(new BigDecimal("0.20")),
                fixture.user
        );

        assertEquals(1, preview.executableModelCount());
        assertFalse(preview.wouldLockOutOrganization());
        assertEquals(ModelRouteOutcome.ALLOWED, preview.models().getFirst().outcome());
        assertTrue(
                new BigDecimal(preview.models().getFirst().estimatedMaxCostUsd())
                        .compareTo(new BigDecimal("0.20")) > 0,
                "Max-envelope cost should remain visible even when a smaller request can pass"
        );
    }

    @Test
    void monthlyBudgetPreviewReadsSnapshotWithoutTakingLiveRoutingLock() {
        Fixture fixture = fixture(List.of(
                entry("openai:gpt-main", "0", "0")
        ));

        when(fixture.decisionRepository.loadCommittedMonthlyCostSnapshot(
                ORGANIZATION_ID,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z")
        )).thenReturn(
                new ModelRouteDecisionRepository.MonthlyCostSnapshot(
                        new BigDecimal("1.00"),
                        0L
                )
        );

        fixture.service.preview(
                ORGANIZATION_ID,
                requestWithBudget(new BigDecimal("10.00")),
                fixture.user
        );

        verify(fixture.decisionRepository, never())
                .lockOrganizationBudget(ORGANIZATION_ID);
        verify(fixture.decisionRepository)
                .loadCommittedMonthlyCostSnapshot(
                        ORGANIZATION_ID,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-10-01T00:00:00Z")
                );
    }

    @Test
    void previewDoesNotPersistPolicyOrRouteDecision() {
        Fixture fixture = fixture(List.of(
                entry("openai:gpt-main", "0", "0")
        ));

        fixture.service.preview(
                ORGANIZATION_ID,
                request(null),
                fixture.user
        );

        verifyNoInteractions(fixture.decisionRepository);
        verify(fixture.policyRepository, never()).insert(
                org.mockito.ArgumentMatchers.any()
        );
    }

    private static Fixture fixture(List<ModelCatalogEntry> catalog) {
        ModelCatalogRepository catalogRepository =
                mock(ModelCatalogRepository.class);
        OrganizationModelPolicyRepository policyRepository =
                mock(OrganizationModelPolicyRepository.class);
        ModelRouteDecisionRepository decisionRepository =
                mock(ModelRouteDecisionRepository.class);
        RuntimeModelStatusService runtimeService =
                mock(RuntimeModelStatusService.class);
        SafeAiUserPrincipal user = mock(SafeAiUserPrincipal.class);
        GrantedAuthority authority = mock(GrantedAuthority.class);

        when(authority.getAuthority())
                .thenReturn(SystemRole.SUPER_ADMIN.authority());
        when(user.getAuthorities()).thenReturn(List.of(authority));
        when(user.getId()).thenReturn(USER_ID);
        when(policyRepository.organizationExists(ORGANIZATION_ID))
                .thenReturn(true);
        when(policyRepository.findLatest(ORGANIZATION_ID))
                .thenReturn(Optional.empty());
        when(catalogRepository.findEffectiveAll(NOW))
                .thenReturn(catalog);
        when(catalogRepository.findEffectiveByRuntime("openai", "gpt-x", NOW))
                .thenReturn(catalog);
        when(catalogRepository.hasEffectiveHistoryByRuntime("openai", "gpt-x", NOW))
                .thenReturn(!catalog.isEmpty());
        when(runtimeService.current()).thenReturn(runtime());

        return new Fixture(
                new ModelPolicyPreviewService(
                        catalogRepository,
                        policyRepository,
                        decisionRepository,
                        runtimeService,
                        new ModelRoutingEnvelopeService(
                                new ModelRoutingEnvelopeProperties(
                                        null, null, null, null, null, null
                                )
                        ),
                        Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                user,
                decisionRepository,
                policyRepository
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest request(
            BigDecimal maxRequestCost
    ) {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                maxRequestCost,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest requestWithBudget(
            BigDecimal monthlyBudget
    ) {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                monthlyBudget,
                BudgetEnforcement.HARD,
                false,
                false,
                false
        );
    }

    private static RuntimeModelStatusResponse runtime() {
        return new RuntimeModelStatusResponse(
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
                "provider-2026-09"
        );
    }

    private static ModelCatalogEntry entry(
            String modelKey,
            String inputRate,
            String outputRate
    ) {
        return new ModelCatalogEntry(
                UUID.randomUUID(),
                modelKey,
                1,
                "openai",
                "gpt-x",
                modelKey,
                ModelLifecycle.ACTIVE,
                64_000,
                8_192,
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.CONFIGURED,
                true,
                new BigDecimal(inputRate),
                null,
                null,
                new BigDecimal(outputRate),
                "{}",
                "provider-2026-09",
                NOW.minusSeconds(3600),
                ModelCatalogSource.MANUAL,
                USER_ID,
                NOW.minusSeconds(7200)
        );
    }

    private record Fixture(
            ModelPolicyPreviewService service,
            SafeAiUserPrincipal user,
            ModelRouteDecisionRepository decisionRepository,
            OrganizationModelPolicyRepository policyRepository
    ) {
    }
}

