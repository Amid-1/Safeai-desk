package ru.safeai.gateway.model.domain;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouteRequestV54ContractTest {
    @Test
    void nullHistoryAndCapabilitiesAreNormalizedToImmutableEmptyCollections() {
        var request = create(null, null, 0L);
        assertThat(request.history()).isEmpty();
        assertThat(request.requiredCapabilities()).isEmpty();
        assertThatThrownBy(() -> request.history().add(
                new AiMessage(AiMessageRole.USER, "tamper")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nonEmptyCollectionsAreSnapshotCopiesAndCannotBeMutated() {
        List<AiMessage> history = new ArrayList<>();
        history.add(new AiMessage(AiMessageRole.USER, "context"));
        EnumSet<ModelCapability> capabilities = EnumSet.of(ModelCapability.TOOLS);
        var request = create(history, capabilities, 500L);
        history.clear();
        capabilities.add(ModelCapability.VISION);
        assertThat(request.history()).hasSize(1);
        assertThat(request.requiredCapabilities()).containsExactly(ModelCapability.TOOLS);
        assertThat(request.additionalInputUnitUpperBound()).isEqualTo(500L);
        assertThatThrownBy(() -> request.requiredCapabilities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void negativeRagEnvelopeIsRejectedBeforeRouting() {
        assertThatThrownBy(() -> create(List.of(), Set.of(), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additionalInputUnitUpperBound");
    }

    @Test
    void absentTenantOrOperationIdentifiersAreRejected() {
        assertThatThrownBy(() -> new ModelRouteRequest(
                null, ModelTestFixtures.USER_ID, ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.TURN_ID, ModelTestFixtures.CLIENT_REQUEST_ID,
                ModelTestFixtures.REQUEST_HASH, null, "message", List.of(), Set.of(), 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("organizationId");
        assertThatThrownBy(() -> new ModelRouteRequest(
                ModelTestFixtures.ORGANIZATION_ID, ModelTestFixtures.USER_ID,
                ModelTestFixtures.CHAT_ID, ModelTestFixtures.TURN_ID, null,
                ModelTestFixtures.REQUEST_HASH, null, "message", List.of(), Set.of(), 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clientRequestId");
    }

    @Test
    void userMessageAndSemanticHashAreRequiredInputs() {
        assertThatThrownBy(() -> new ModelRouteRequest(
                ModelTestFixtures.ORGANIZATION_ID, ModelTestFixtures.USER_ID,
                ModelTestFixtures.CHAT_ID, ModelTestFixtures.TURN_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID, null, null,
                "hello", List.of(), Set.of(), 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("requestContentHash");
        assertThatThrownBy(() -> new ModelRouteRequest(
                ModelTestFixtures.ORGANIZATION_ID, ModelTestFixtures.USER_ID,
                ModelTestFixtures.CHAT_ID, ModelTestFixtures.TURN_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID,
                ModelTestFixtures.REQUEST_HASH, null,
                null, List.of(), Set.of(), 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userMessage");
    }

    private static ModelRouteRequest create(
            List<AiMessage> history, Set<ModelCapability> capabilities, long extra
    ) {
        return new ModelRouteRequest(
                ModelTestFixtures.ORGANIZATION_ID, ModelTestFixtures.USER_ID,
                ModelTestFixtures.CHAT_ID, ModelTestFixtures.TURN_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID, ModelTestFixtures.REQUEST_HASH,
                null, "hello", history, capabilities, extra);
    }
}
