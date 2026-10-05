package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelAuditDetailsFactoryTest {

    @Test
    void catalogAuditSnapshotContainsBoundedGovernanceIdentity() {
        var entry = ModelTestFixtures.configuredEntry();

        Map<String, Object> details =
                ModelCatalogAuditDetailsFactory.create(entry).toMap();

        assertThat(details)
                .containsEntry("catalogEntryId", entry.id())
                .containsEntry("modelKey", entry.modelKey())
                .containsEntry("version", entry.version())
                .containsEntry("provider", entry.provider())
                .containsEntry("providerModelId", entry.providerModelId())
                .containsEntry("lifecycle", entry.lifecycle())
                .containsEntry("pricingStatus", entry.pricingStatus())
                .containsEntry("pricingComplete", entry.pricingComplete())
                .containsEntry("source", entry.source())
                .containsEntry("maxInputTokens", entry.maxInputTokens())
                .containsEntry("maxOutputTokens", entry.maxOutputTokens())
                .containsEntry("capabilities", entry.capabilities())
                .containsEntry("inputModalities", entry.inputModalities())
                .containsEntry("outputModalities", entry.outputModalities())
                .containsEntry("retentionStatus", entry.retentionStatus())
                .containsEntry("trainingUseStatus", entry.trainingUseStatus())
                .containsEntry("effectiveFrom", entry.effectiveFrom().toString())
                .containsEntry("pricingVersion", entry.pricingVersion());

    }

    @Test
    void policyAuditUsesExactDecimalStringsAndDeterministicModelKeyEvidence() {
        var policy = ModelTestFixtures.policy(
                true,
                Set.of("z:model", "a:model"),
                Set.of("blocked:model"),
                "a:model",
                10_000,
                2_000,
                new java.math.BigDecimal("1.230000000000"),
                new java.math.BigDecimal("9.870000000000"),
                BudgetEnforcement.HARD,
                true,
                true,
                true
        );

        Map<String, Object> first = OrganizationModelPolicyAuditDetailsFactory.create(policy);
        Map<String, Object> second = OrganizationModelPolicyAuditDetailsFactory.create(policy);

        assertThat(first)
                .containsEntry("policyId", policy.id())
                .containsEntry("organizationId", policy.organizationId())
                .containsEntry("version", policy.version())
                .containsEntry("enabled", true)
                .containsEntry("defaultModelKey", "a:model")
                .containsEntry("maxInputTokens", 10_000)
                .containsEntry("maxOutputTokens", 2_000)
                .containsEntry("maxRequestCostUsd", "1.230000000000")
                .containsEntry("monthlyBudgetUsd", "9.870000000000")
                .containsEntry("budgetEnforcement", BudgetEnforcement.HARD)
                .containsEntry("requireCompletePricing", true)
                .containsEntry("requireNoTraining", true)
                .containsEntry("requireZeroDataRetention", true)
                .containsEntry("allowModelKeyCount", 2)
                .containsEntry("denyModelKeyCount", 1)
                .containsEntry("modelKeyListsIncluded", true)
                .containsEntry("allowModelKeys", policy.allowModelKeys())
                .containsEntry("denyModelKeys", policy.denyModelKeys());

        assertThat(first.get("allowModelKeysSha256"))
                .isEqualTo(second.get("allowModelKeysSha256"));
        assertThat(first.get("denyModelKeysSha256"))
                .isEqualTo(second.get("denyModelKeysSha256"));
        assertThat(first.get("allowModelKeysSha256").toString()).matches("[0-9a-f]{64}");
        assertThat(first.get("denyModelKeysSha256").toString()).matches("[0-9a-f]{64}");
    }

    @Test
    void oversizedPolicyModelKeyListsRemainAuditableWithoutInliningPayload() {
        LinkedHashSet<String> allow = new LinkedHashSet<>();
        for (int i = 0; i < 200; i++) {
            allow.add("model-" + String.format("%03d", i) + "-" + "x".repeat(90));
        }
        var policy = ModelTestFixtures.policy(
                true, allow, Set.of(), null, null, null, null, null,
                BudgetEnforcement.SOFT, false, false, false
        );

        Map<String, Object> details = OrganizationModelPolicyAuditDetailsFactory.create(policy);

        assertThat(details)
                .containsEntry("allowModelKeyCount", 200)
                .containsEntry("denyModelKeyCount", 0)
                .containsEntry("modelKeyListsIncluded", false)
                .containsKeys("allowModelKeysSha256", "denyModelKeysSha256")
                .doesNotContainKeys("allowModelKeys", "denyModelKeys");
    }

    @Test
    void auditFactoriesRejectNullSnapshots() {
        assertThatThrownBy(() -> ModelCatalogAuditDetailsFactory.create(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
        assertThatThrownBy(() -> OrganizationModelPolicyAuditDetailsFactory.create(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("policy");
    }
}
