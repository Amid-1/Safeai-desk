package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationModelPolicyServiceTest {

    @Mock
    private OrganizationModelPolicyRepository repository;

    @Mock
    private AuditEventService audit;

    private OrganizationModelPolicyService service;

    @BeforeEach
    void setUp() {
        service = new OrganizationModelPolicyService(
                repository,
                audit,
                ModelTestFixtures.CLOCK
        );
    }

    @Test
    void adminCanReadOwnUnconfiguredPolicy() {
        when(repository.organizationExists(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(true);
        when(repository.findLatest(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.empty());

        var response = service.current(
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.adminPrincipal()
        );

        assertThat(response.configured())
                .isFalse();
        assertThat(response.organizationId())
                .isEqualTo(ModelTestFixtures.ORGANIZATION_ID);
        assertThat(response.version())
                .isZero();
    }

    @Test
    void superAdminGetsNotFoundForMissingOrganizationInsteadOfFakeUnconfiguredPolicy() {
        when(repository.organizationExists(
                ModelTestFixtures.OTHER_ORGANIZATION_ID
        )).thenReturn(false);

        assertThatThrownBy(() -> service.current(
                ModelTestFixtures.OTHER_ORGANIZATION_ID,
                ModelTestFixtures.superAdminPrincipal()
        )).isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never())
                .findLatest(ModelTestFixtures.OTHER_ORGANIZATION_ID);
    }

    @Test
    void ordinaryUserCannotReadPolicy() {
        assertThatThrownBy(() -> service.current(
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.userPrincipal()
        )).isInstanceOf(ForbiddenOperationException.class);

        verify(repository, never())
                .organizationExists(any());
    }

    @Test
    void tenantAdminCannotReadAnotherOrganizationPolicy() {
        assertThatThrownBy(() -> service.current(
                ModelTestFixtures.OTHER_ORGANIZATION_ID,
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void superAdminCanReadAnotherOrganizationPolicy() {
        OrganizationModelPolicy policy = new OrganizationModelPolicy(
                ModelTestFixtures.POLICY_ID,
                ModelTestFixtures.OTHER_ORGANIZATION_ID,
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
                ModelTestFixtures.USER_ID,
                ModelTestFixtures.NOW
        );
        when(repository.organizationExists(
                ModelTestFixtures.OTHER_ORGANIZATION_ID
        )).thenReturn(true);
        when(repository.findLatest(
                ModelTestFixtures.OTHER_ORGANIZATION_ID
        )).thenReturn(Optional.of(policy));

        var response = service.current(
                ModelTestFixtures.OTHER_ORGANIZATION_ID,
                ModelTestFixtures.superAdminPrincipal()
        );

        assertThat(response.configured())
                .isTrue();
        assertThat(response.organizationId())
                .isEqualTo(ModelTestFixtures.OTHER_ORGANIZATION_ID);
    }

    @Test
    void createLocksOrganizationBeforeVersionAllocation() {
        when(repository.lockOrganization(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(true);
        when(repository.findLatest(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.empty());

        service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                validRequest(),
                ModelTestFixtures.adminPrincipal()
        );

        var inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository)
                .lockOrganization(ModelTestFixtures.ORGANIZATION_ID);
        inOrder.verify(repository)
                .findLatest(ModelTestFixtures.ORGANIZATION_ID);
        inOrder.verify(repository)
                .insert(any(OrganizationModelPolicy.class));
    }

    @Test
    void missingOrganizationFailsBeforePolicyLookupOrInsert() {
        when(repository.lockOrganization(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(false);

        assertThatThrownBy(() -> service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                validRequest(),
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never())
                .findLatest(any());
        verify(repository, never())
                .insert(any());
    }

    @Test
    void optimisticVersionConflictPreventsInsert() {
        when(repository.lockOrganization(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(true);
        when(repository.findLatest(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.of(ModelTestFixtures.hardPolicy()));

        assertThatThrownBy(() -> service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                validRequest(),
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(ConflictException.class);

        verify(repository, never()).insert(any());
    }

    @Test
    void modelKeysAreCanonicalizedDeduplicatedAndSorted() {
        when(repository.lockOrganization(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(true);
        when(repository.findLatest(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.empty());

        CreateOrganizationModelPolicyVersionRequest request =
                canonicalizationRequest();

        service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                request,
                ModelTestFixtures.adminPrincipal()
        );

        ArgumentCaptor<OrganizationModelPolicy> captor =
                ArgumentCaptor.forClass(OrganizationModelPolicy.class);
        verify(repository).insert(captor.capture());

        assertThat(captor.getValue().allowModelKeys())
                .containsExactly("a:model", "z:model");
        assertThat(captor.getValue().defaultModelKey())
                .isEqualTo("a:model");
    }

    @Test
    void overlappingAllowAndDenyListsAreRejected() {
        CreateOrganizationModelPolicyVersionRequest request =
                overlappingListsRequest();

        stubReadyForFirstVersion();

        assertThatThrownBy(() -> service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                request,
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(BadRequestException.class);

        verify(repository, never()).insert(any());
    }

    @Test
    void defaultModelMustBelongToNonEmptyAllowList() {
        CreateOrganizationModelPolicyVersionRequest request =
                defaultOutsideAllowListRequest();

        stubReadyForFirstVersion();

        assertThatThrownBy(() -> service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                request,
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(BadRequestException.class);
    }

    @Test
    void invalidNumericBudgetIsRejectedAtServiceBoundary() {
        CreateOrganizationModelPolicyVersionRequest request =
                invalidNumericBudgetRequest();

        stubReadyForFirstVersion();

        assertThatThrownBy(() -> service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                request,
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(BadRequestException.class);
    }

    @Test
    void nullBudgetEnforcementFailsAsBadRequestEvenWhenBeanValidationIsBypassed() {
        CreateOrganizationModelPolicyVersionRequest request =
                nullBudgetEnforcementRequest();

        stubReadyForFirstVersion();

        assertThatThrownBy(() -> service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                request,
                ModelTestFixtures.adminPrincipal()
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("budgetEnforcement");
    }

    @Test
    void monetaryPolicyValuesAreCanonicalizedToDatabaseScaleBeforeInsert() {
        stubReadyForFirstVersion();

        CreateOrganizationModelPolicyVersionRequest request =
                monetaryCanonicalizationRequest();

        service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                request,
                ModelTestFixtures.adminPrincipal()
        );

        ArgumentCaptor<OrganizationModelPolicy> captor =
                ArgumentCaptor.forClass(OrganizationModelPolicy.class);
        verify(repository).insert(captor.capture());

        assertThat(captor.getValue().maxRequestCostUsd())
                .isEqualTo(
                        new BigDecimal("5.000000000000")
                );
        assertThat(captor.getValue().monthlyBudgetUsd())
                .isEqualTo(
                        new BigDecimal("50.500000000000")
                );
    }

    @Test
    void largePolicyListsUseCompactAuditEvidenceInsteadOfRiskingAuditRowOverflow() {
        stubReadyForFirstVersion();

        CreateOrganizationModelPolicyVersionRequest request =
                largeAllowListRequest();

        var principal =
                ModelTestFixtures.adminPrincipal();

        service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                request,
                principal
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass(Map.class);

        verify(audit).record(
                same(principal),
                eq(ModelTestFixtures.ORGANIZATION_ID),
                eq(AuditEventType.MODEL_POLICY_VERSION_CREATED),
                captor.capture()
        );

        assertThat(captor.getValue())
                .containsEntry("organizationId", ModelTestFixtures.ORGANIZATION_ID)
                .containsEntry("version", 1)
                .containsEntry("enabled", true)
                .containsEntry("budgetEnforcement", BudgetEnforcement.SOFT)
                .containsEntry("allowModelKeyCount", 200)
                .containsEntry("denyModelKeyCount", 0)
                .containsEntry("modelKeyListsIncluded", false)
                .containsKeys(
                        "policyId",
                        "allowModelKeysSha256",
                        "denyModelKeysSha256"
                )
                .doesNotContainKeys(
                        "allowModelKeys",
                        "denyModelKeys"
                );

        assertThat(captor.getValue().get("allowModelKeysSha256"))
                .asString()
                .matches("[0-9a-f]{64}");

        assertThat(captor.getValue().get("denyModelKeysSha256"))
                .asString()
                .matches("[0-9a-f]{64}");
    }

    @Test
    void adminCannotMutateAnotherTenantButSuperAdminCan() {
        assertThatThrownBy(() -> service.createVersion(
                ModelTestFixtures.OTHER_ORGANIZATION_ID,
                validRequest(),
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(ForbiddenOperationException.class);

        when(repository.lockOrganization(
                ModelTestFixtures.OTHER_ORGANIZATION_ID
        )).thenReturn(true);
        when(repository.findLatest(
                ModelTestFixtures.OTHER_ORGANIZATION_ID
        )).thenReturn(Optional.empty());

        service.createVersion(
                ModelTestFixtures.OTHER_ORGANIZATION_ID,
                validRequest(),
                ModelTestFixtures.superAdminPrincipal()
        );

        verify(repository)
                .insert(any(OrganizationModelPolicy.class));
    }

    @Test
    void policyAuditContainsEffectiveGovernanceLimits() {
        stubReadyForFirstVersion();

        var principal =
                ModelTestFixtures.adminPrincipal();

        service.createVersion(
                ModelTestFixtures.ORGANIZATION_ID,
                validRequest(),
                principal
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass(Map.class);

        verify(audit).record(
                same(principal),
                eq(ModelTestFixtures.ORGANIZATION_ID),
                eq(AuditEventType.MODEL_POLICY_VERSION_CREATED),
                captor.capture()
        );

        assertThat(captor.getValue())
                .containsEntry("organizationId", ModelTestFixtures.ORGANIZATION_ID)
                .containsEntry("version", 1)
                .containsEntry("enabled", true)
                .containsEntry("defaultModelKey", "openai:gpt-test")
                .containsEntry("budgetEnforcement", BudgetEnforcement.HARD)
                .containsEntry("requireCompletePricing", true)
                .containsEntry("requireNoTraining", true)
                .containsEntry("requireZeroDataRetention", true)
                .containsEntry("maxInputTokens", 8_000)
                .containsEntry("maxOutputTokens", 1_000)
                .containsEntry(
                        "maxRequestCostUsd",
                        "5.000000000000"
                )
                .containsEntry(
                        "monthlyBudgetUsd",
                        "50.000000000000"
                )
                .containsEntry("allowModelKeyCount", 1)
                .containsEntry("denyModelKeyCount", 0)
                .containsEntry("modelKeyListsIncluded", true)
                .containsKeys(
                        "policyId",
                        "allowModelKeysSha256",
                        "denyModelKeysSha256",
                        "allowModelKeys",
                        "denyModelKeys"
                );
    }

    private void stubReadyForFirstVersion() {
        when(repository.lockOrganization(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(true);
        when(repository.findLatest(
                ModelTestFixtures.ORGANIZATION_ID
        )).thenReturn(Optional.empty());
    }

    private static CreateOrganizationModelPolicyVersionRequest
    canonicalizationRequest() {
        Set<String> allow = new LinkedHashSet<>();
        allow.add(" Z:Model ");
        allow.add("a:model");
        allow.add("z:model");

        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                allow,
                Set.of(),
                " A:MODEL ",
                null,
                null,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest
    overlappingListsRequest() {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                Set.of("openai:gpt-test"),
                Set.of(" OPENAI:GPT-TEST "),
                null,
                null,
                null,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest
    defaultOutsideAllowListRequest() {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                Set.of("openai:a"),
                Set.of(),
                "openai:b",
                null,
                null,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest
    invalidNumericBudgetRequest() {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                new BigDecimal("0.0000000000001"),
                BudgetEnforcement.HARD,
                false,
                false,
                false
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest
    nullBudgetEnforcementRequest() {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest
    monetaryCanonicalizationRequest() {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                new BigDecimal("5"),
                new BigDecimal("50.5"),
                BudgetEnforcement.HARD,
                false,
                false,
                false
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest
    largeAllowListRequest() {
        Set<String> allow = new LinkedHashSet<>();

        for (int index = 0; index < 200; index++) {
            allow.add(
                    "m"
                            + index
                            + ":"
                            + "a".repeat(150)
            );
        }

        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                allow,
                Set.of(),
                null,
                null,
                null,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false
        );
    }

    private static CreateOrganizationModelPolicyVersionRequest validRequest() {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                true,
                Set.of("openai:gpt-test"),
                Set.of(),
                "openai:gpt-test",
                8_000,
                1_000,
                new BigDecimal("5.000000000000"),
                new BigDecimal("50.000000000000"),
                BudgetEnforcement.HARD,
                true,
                true,
                true
        );
    }
}
