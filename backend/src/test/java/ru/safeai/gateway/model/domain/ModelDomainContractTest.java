package ru.safeai.gateway.model.domain;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelDomainContractTest {

    @Test
    void catalogEntryDefensivelyCopiesCapabilityAndModalitySets() {
        LinkedHashSet<ModelCapability> capabilities =
                new LinkedHashSet<>();
        capabilities.add(ModelCapability.VISION);

        ModelCatalogEntry entry = ModelTestFixtures.entry(
                ModelLifecycle.ACTIVE,
                ModelPricingStatus.FREE,
                true,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                capabilities,
                ModelRetentionStatus.NOT_DECLARED,
                ModelTrainingUseStatus.NOT_DECLARED
        );

        capabilities.clear();

        assertThat(entry.capabilities())
                .containsExactly(ModelCapability.VISION);

        assertThatThrownBy(() ->
                entry.capabilities().add(
                        ModelCapability.TOOLS
                )
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

    @Test
    void organizationPolicyCanonicalSetOrderIsStableAndImmutable() {
        LinkedHashSet<String> allow =
                new LinkedHashSet<>();

        allow.add("z:model");
        allow.add("a:model");

        OrganizationModelPolicy policy =
                ModelTestFixtures.policy(
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

        allow.clear();

        assertThat(policy.allowModelKeys())
                .containsExactly(
                        "a:model",
                        "z:model"
                );

        assertThatThrownBy(() ->
                policy.allowModelKeys().add(
                        "x:model"
                )
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

    @Test
    void routeRequestDefensivelyCopiesHistoryAndCapabilities() {
        ArrayList<ru.safeai.gateway.ai.dto.AiMessage> history =
                new ArrayList<>();

        history.add(
                new ru.safeai.gateway.ai.dto.AiMessage(
                        "user",
                        "history"
                )
        );

        LinkedHashSet<ModelCapability> capabilities =
                new LinkedHashSet<>();

        capabilities.add(
                ModelCapability.STRUCTURED_OUTPUT
        );

        ModelRouteRequest request =
                new ModelRouteRequest(
                        ModelTestFixtures.ORGANIZATION_ID,
                        ModelTestFixtures.USER_ID,
                        ModelTestFixtures.CHAT_ID,
                        ModelTestFixtures.TURN_ID,
                        ModelTestFixtures.CLIENT_REQUEST_ID,
                        ModelTestFixtures.REQUEST_HASH,
                        null,
                        "hello",
                        history,
                        capabilities,
                        0L
                );

        history.clear();
        capabilities.clear();

        assertThat(request.history())
                .hasSize(1);

        assertThat(
                request.requiredCapabilities()
        ).containsExactly(
                ModelCapability.STRUCTURED_OUTPUT
        );

        assertThatThrownBy(() ->
                request.history().add(
                        new ru.safeai.gateway.ai.dto.AiMessage(
                                "user",
                                "other"
                        )
                )
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

    @Test
    void responseFactoriesPreserveImmutableEvidenceFields() {
        ModelCatalogEntry entry =
                ModelTestFixtures.freeEntry();

        var response =
                ru.safeai.gateway.model.dto.ModelCatalogEntryResponse.from(
                        entry
                );

        assertThat(response.id())
                .isEqualTo(entry.id());

        assertThat(response.modelKey())
                .isEqualTo(entry.modelKey());

        assertThat(response.version())
                .isEqualTo(entry.version());

        assertThat(response.createdAt())
                .isEqualTo(entry.createdAt());
    }
    @Test
    void routeResultPreservesTheExactExecutableSnapshotWithoutNumericCoercion() {
        java.math.BigDecimal cost = new java.math.BigDecimal("0.123456789012");
        ModelRouteResult result = new ModelRouteResult(
                ModelTestFixtures.CLIENT_REQUEST_ID,
                ModelTestFixtures.CATALOG_ENTRY_ID,
                2,
                ModelTestFixtures.POLICY_ID,
                3,
                "openai:gpt-test",
                "openai",
                "gpt-test",
                12_345L,
                4_096L,
                cost,
                true,
                false,
                ModelRouteReason.REQUESTED_MODEL
        );

        assertThat(result.decisionId()).isEqualTo(ModelTestFixtures.CLIENT_REQUEST_ID);
        assertThat(result.catalogEntryId()).isEqualTo(ModelTestFixtures.CATALOG_ENTRY_ID);
        assertThat(result.catalogVersion()).isEqualTo(2);
        assertThat(result.policyId()).isEqualTo(ModelTestFixtures.POLICY_ID);
        assertThat(result.policyVersion()).isEqualTo(3);
        assertThat(result.modelKey()).isEqualTo("openai:gpt-test");
        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.providerModelId()).isEqualTo("gpt-test");
        assertThat(result.estimatedInputTokens()).isEqualTo(12_345L);
        assertThat(result.estimatedOutputTokens()).isEqualTo(4_096L);
        assertThat(result.estimatedMaxCostUsd()).isSameAs(cost);
        assertThat(result.pricingComplete()).isTrue();
        assertThat(result.budgetExceeded()).isFalse();
        assertThat(result.reason()).isEqualTo(ModelRouteReason.REQUESTED_MODEL);
    }

}
