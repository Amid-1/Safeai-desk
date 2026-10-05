package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ModelInputTokenEstimatorIntegrationTest {

    private final UUID chatId = UUID.randomUUID();
    private final UUID turnId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final UUID operationId = UUID.randomUUID();

    private final KnowledgeMode withKnowledge = Arrays.stream(KnowledgeMode.values())
            .filter(KnowledgeMode::usesKnowledge)
            .findFirst()
            .orElseThrow();

    private final KnowledgeMode withoutKnowledge = Arrays.stream(KnowledgeMode.values())
            .filter(mode -> !mode.usesKnowledge())
            .findFirst()
            .orElseThrow();

    @Test
    void routeCostIncludesUtf8HistoryAndTheEntireAdditionalReservation() {
        ModelRouteRequest request = route(
                "I",
                List.of(new AiMessage("assistant", "Привет")),
                257L
        );

        long expected = 286L;
        // UTF8_STRUCTURAL_UNITS_V2:
        // current user: 1 + 8
        // history assistant "Привет": 12 + 8
        // additional reservation: 257

        ModelRoutingCostPolicy policy =
                new ModelRoutingCostPolicy(mock(ModelRouteDecisionRepository.class));

        assertThat(ModelInputTokenEstimator.estimateRouteRequest(request))
                .isEqualTo(expected);
        assertThat(policy.estimateInputTokens(request))
                .isEqualTo(expected);
    }

    @Test
    void overflowingAdditionalReservationIsRejectedBeforeAUsableEstimateIsReturned() {
        ModelRouteRequest request = route(
                "hello",
                List.of(),
                Long.MAX_VALUE
        );

        ModelRoutingCostPolicy policy =
                new ModelRoutingCostPolicy(mock(ModelRouteDecisionRepository.class));

        assertThatThrownBy(() ->
                ModelInputTokenEstimator.estimateRouteRequest(request))
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() ->
                policy.estimateInputTokens(request))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void preparedEstimateIncludesSystemDeveloperHistoryAndMultibyteText() {
        AiChatRequest prepared = request(
                "система",
                "developer",
                "😀",
                List.of(
                        new AiMessage("user", "Q"),
                        new AiMessage("assistant", "Hi")
                ),
                200L
        );

        /*
         * UTF8_STRUCTURAL_UNITS_V2:
         * current user "😀"       = 4 + 8  = 12
         * history USER "Q"        = 1 + 8  =  9
         * history ASSISTANT "Hi"  = 2 + 8  = 10
         * system "система"        = 14 + 8 = 22
         * developer "developer"   = 9 + 8  = 17
         * ---------------------------------------
         * total                            = 70
         *
         * The USER -> ASSISTANT pair is intentional: AiChatRequest accepts only
         * complete canonical chronological history turns.
         */
        assertThat(ModelInputTokenEstimator.estimatePreparedRequest(prepared))
                .isEqualTo(70L);
    }

    @Test
    void ragAppendAtTheExactReservedBoundaryIsAccepted() {
        AiChatRequest prepared = request(
                "base\n\nконтекст",
                "developer",
                "hello",
                List.of(),
                1_000L
        );

        long cap =
                ModelInputTokenEstimator.estimatePreparedRequest(prepared);

        AiChatRequest reserved = request(
                "base",
                "developer",
                "hello",
                List.of(),
                cap
        );

        prepared = request(
                prepared.systemInstructions(),
                prepared.developerInstructions(),
                "hello",
                List.of(),
                cap
        );

        AiChatRequest finalPrepared = prepared;
        ModelRouteExecutionIdentity identity =
                identity(reserved, withKnowledge);

        assertThatCode(() ->
                ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                        identity,
                        reserved,
                        finalPrepared
                ))
                .doesNotThrowAnyException();
    }

    @Test
    void oneAdditionalUnitBeyondTheReservationIsRejected() {
        AiChatRequest reserved = request(
                "base",
                "developer",
                "hello",
                List.of(),
                66L
        );

        AiChatRequest prepared = request(
                "base\n\n" + "x".repeat(23),
                "developer",
                "hello",
                List.of(),
                66L
        );

        ModelRouteExecutionIdentity identity =
                identity(reserved, withKnowledge);

        assertThat(ModelInputTokenEstimator.estimatePreparedRequest(prepared))
                .isEqualTo(67L);

        assertThatThrownBy(() ->
                ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                        identity,
                        reserved,
                        prepared
                ))
                .isInstanceOf(ModelRouteEnvelopeExceededException.class);
    }

    @Test
    void changedRagPrefixIsRejectedEvenWhenItsEstimateFits() {
        AiChatRequest reserved = request(
                "base",
                null,
                "hello",
                List.of(),
                1_000L
        );

        AiChatRequest prepared = request(
                "replaced",
                null,
                "hello",
                List.of(),
                1_000L
        );

        ModelRouteExecutionIdentity identity =
                identity(reserved, withKnowledge);

        assertThatThrownBy(() ->
                ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                        identity,
                        reserved,
                        prepared
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("original instruction prefix");
    }

    @Test
    void nonRagRouteCannotIntroduceInstructionsOrChangeItsBase() {
        AiChatRequest reserved = request(
                null,
                null,
                "hello",
                List.of(),
                1_000L
        );

        ModelRouteExecutionIdentity identity =
                identity(reserved, withoutKnowledge);

        assertThatThrownBy(() ->
                ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                        identity,
                        reserved,
                        request(
                                "system",
                                null,
                                "hello",
                                List.of(),
                                1_000L
                        )
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Non-RAG route changed instructions");

        assertThatThrownBy(() ->
                ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                        identity,
                        reserved,
                        request(
                                null,
                                null,
                                "changed",
                                List.of(),
                                1_000L
                        )
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different immutable base identity");
    }

    private ModelRouteRequest route(
            String text,
            List<AiMessage> history,
            long additional
    ) {
        return new ModelRouteRequest(
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.USER_ID,
                chatId,
                turnId,
                clientId,
                ModelTestFixtures.REQUEST_HASH,
                "openai:gpt-test",
                text,
                history,
                Set.of(),
                additional
        );
    }

    private AiChatRequest request(
            String system,
            String developer,
            String text,
            List<AiMessage> history,
            long cap
    ) {
        return new AiChatRequest(
                ModelTestFixtures.USER_ID,
                ModelTestFixtures.ORGANIZATION_ID,
                chatId,
                operationId,
                system,
                developer,
                text,
                history,
                cap,
                2_000
        );
    }

    private ModelRouteExecutionIdentity identity(
            AiChatRequest reserved,
            KnowledgeMode mode
    ) {
        return new ModelRouteExecutionIdentity(
                UUID.randomUUID(),
                ModelTestFixtures.freeEntry().id(),
                1,
                "openai:gpt-test",
                reserved.organizationId(),
                reserved.userId(),
                reserved.chatId(),
                turnId,
                clientId,
                operationId,
                ModelTestFixtures.REQUEST_HASH,
                ModelRouteExecutionIdentity.baseSha256(reserved),
                AiInputUnitEstimator.VERSION,
                reserved.reservedInputTokens(),
                2_000,
                "openai",
                "gpt-test",
                mode.usesKnowledge() ? UUID.randomUUID() : null,
                mode
        );
    }
}
