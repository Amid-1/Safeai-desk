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
}
