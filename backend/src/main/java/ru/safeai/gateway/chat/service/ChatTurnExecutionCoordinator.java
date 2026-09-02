package ru.safeai.gateway.chat.service;

import lombok.extern.slf4j.Slf4j;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.dto.SendMessageResponse;
import ru.safeai.gateway.chat.exception.AiOutcomeAmbiguousException;
import ru.safeai.gateway.chat.exception.ChatLeaseUnavailableException;
import ru.safeai.gateway.chat.exception.ChatStaleProcessorException;
import ru.safeai.gateway.chat.exception.ChatTurnFailedException;
import ru.safeai.gateway.common.exception.ChatLockUnavailableException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagService;
import ru.safeai.gateway.knowledge.rag.RagCompletion;
import ru.safeai.gateway.knowledge.rag.RagPreparation;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;
import ru.safeai.gateway.model.service.ModelRouteExecutionGuard;

import java.util.Objects;
import java.util.UUID;

/**
 * Coordinates one chat turn across the Redis lock, durable DB lease,
 * RAG boundary, provider I/O and terminal persistence.
 *
 * <p>This class deliberately has no Spring transaction annotation. Durable
 * state transitions remain owned by the transactional reservation and
 * finalization services. Keeping external provider I/O outside a database
 * transaction is a core correctness requirement.</p>
 */
@Slf4j
final class ChatTurnExecutionCoordinator {

    private final AiProvider aiProvider;
    private final ChatTurnReservationService reservationService;
    private final ChatTurnFinalizationService finalizationService;
    private final ChatSecurityStateService securityStateService;
    private final ChatLockService lockService;
    private final ChatTurnLeaseService leaseService;
    private final KnowledgeRagService ragService;

    /**
     * Constructor intentionally retains the pre-V46 signature so ChatService
     * and existing tests/wiring do not lose functionality.
     */
    ChatTurnExecutionCoordinator(
            AiProvider aiProvider,
            ChatTurnReservationService reservationService,
            ChatTurnFinalizationService finalizationService,
            ChatSecurityStateService securityStateService,
            ChatLockService lockService,
            ChatTurnLeaseService leaseService,
            KnowledgeRagService ragService
    ) {
        this.aiProvider = Objects.requireNonNull(
                aiProvider,
                "aiProvider не должен быть null"
        );
        this.reservationService = Objects.requireNonNull(
                reservationService,
                "reservationService не должен быть null"
        );
        this.finalizationService = Objects.requireNonNull(
                finalizationService,
                "finalizationService не должен быть null"
        );
        this.securityStateService = Objects.requireNonNull(
                securityStateService,
                "securityStateService не должен быть null"
        );
        this.lockService = Objects.requireNonNull(
                lockService,
                "lockService не должен быть null"
        );
        this.leaseService = Objects.requireNonNull(
                leaseService,
                "leaseService не должен быть null"
        );
        this.ragService = Objects.requireNonNull(
                ragService,
                "ragService не должен быть null"
        );
    }

    SendMessageResponse execute(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        ChatLockService.ChatLock redisLock =
                lockService.lock(chatId);

        ChatTurnLeaseService.LeaseWatch leaseWatch =
                null;

        try {
            ChatProcessingContext context =
                    reservationService.reserveOrReplay(
                            chatId,
                            request,
                            currentUser
                    );

            if (context.replay()) {
                return finalizationService.replaySucceeded(
                        context,
                        currentUser
                );
            }

            leaseWatch =
                    startLeaseWatch(
                            context,
                            currentUser
                    );

            AiChatRequest reservedAiRequest =
                    Objects.requireNonNull(
                            context.aiRequest(),
                            "reserved aiRequest не должен быть null"
                    );

            RagPreparation ragPreparation =
                    prepareRag(
                            context,
                            currentUser
                    );

            AiChatRequest preparedAiRequest =
                    Objects.requireNonNull(
                            ragPreparation.aiRequest(),
                            "ragPreparation.aiRequest не должен быть null"
                    );

            enforceReservedRouteEnvelope(
                    context,
                    reservedAiRequest,
                    preparedAiRequest,
                    currentUser
            );

            context =
                    context.withAiRequest(
                            preparedAiRequest
                    );

            /*
             * Knowledge ACL contract: successful prepare(...) authorizes this
             * turn and materializes retrieval context. ACL changes after this
             * point apply to future turns; strict in-flight revocation is not
             * claimed by the current contract.
             */

            finalizationService.markProviderCallStarted(
                    context
            );

            ensureOwnershipBeforeProvider(
                    context,
                    redisLock,
                    leaseWatch,
                    currentUser
            );

            AiChatResponse providerResponse =
                    invokeProvider(
                            context,
                            currentUser
                    );

            RagCompletion ragCompletion =
                    completeRag(
                            context,
                            ragPreparation,
                            providerResponse,
                            currentUser
                    );

            AiChatResponse response =
                    ragCompletion.response();

            ensureOwnershipAfterProvider(
                    context,
                    response,
                    redisLock,
                    leaseWatch,
                    currentUser
            );

            SendMessageResponse result =
                    persistSuccess(
                            context,
                            ragCompletion,
                            response,
                            currentUser
                    );

            securityStateService.assertStillActive(
                    context.chatId(),
                    context.turnId(),
                    context.clientRequestId(),
                    currentUser
            );

            return result;
        } finally {
            leaseService.close(
                    leaseWatch
            );

            lockService.unlockQuietly(
                    redisLock
            );
        }
    }

    private ChatTurnLeaseService.LeaseWatch startLeaseWatch(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            return leaseService.watch(
                    context.chatId(),
                    context.turnId(),
                    context.clientRequestId(),
                    context.processingToken()
            );
        } catch (ChatLeaseUnavailableException exception) {
            persistPreProviderFailureQuietly(
                    context,
                    "LEASE_WATCHDOG_UNAVAILABLE",
                    "CHAT_LEASE_WATCHDOG_UNAVAILABLE",
                    currentUser,
                    exception
            );
            throw exception;
        }
    }

    private RagPreparation prepareRag(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            return Objects.requireNonNull(
                    ragService.prepare(
                            context,
                            currentUser
                    ),
                    "ragPreparation не должен быть null"
            );
        } catch (RuntimeException exception) {
            persistPreProviderFailureQuietly(
                    context,
                    "RAG_PREPARATION_FAILED",
                    "KNOWLEDGE_RAG_PREPARATION_FAILED",
                    currentUser,
                    exception
            );

            throw new ChatTurnFailedException(
                    context.chatId(),
                    context.turnId(),
                    context.clientRequestId(),
                    "KNOWLEDGE_RAG_PREPARATION_FAILED",
                    exception
            );
        }
    }

    private void enforceReservedRouteEnvelope(
            ChatProcessingContext context,
            AiChatRequest reservedRequest,
            AiChatRequest preparedRequest,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            UUID decisionId =
                    context.modelRouteDecisionId();

            if (decisionId == null) {
                /*
                 * Compatibility for legacy/direct coordinator tests. Real V46
                 * reserved turns carry a persisted model-route decision id.
                 */
                ModelRouteExecutionGuard
                        .assertWithinReservedInputEnvelope(
                                reservedRequest,
                                preparedRequest
                        );
            } else {
                ModelRouteExecutionGuard
                        .assertWithinReservedInputEnvelope(
                                decisionId,
                                reservedRequest,
                                preparedRequest
                        );
            }
        } catch (ModelRouteEnvelopeExceededException exception) {
            log.warn(
                    "Model route input envelope exceeded before provider call: "
                            + "chatId={}, turnId={}, decisionId={}, "
                            + "reservedInputTokens={}, actualEstimatedInputTokens={}",
                    context.chatId(),
                    context.turnId(),
                    exception.decisionId(),
                    exception.reservedInputTokens(),
                    exception.actualEstimatedInputTokens()
            );

            failRouteEnvelope(
                    context,
                    currentUser,
                    exception
            );
        } catch (IllegalStateException exception) {
            log.warn(
                    "Model route execution envelope integrity violation before provider call: "
                            + "chatId={}, turnId={}, decisionId={}, error={}",
                    context.chatId(),
                    context.turnId(),
                    context.modelRouteDecisionId(),
                    exception.getMessage()
            );

            failRouteEnvelope(
                    context,
                    currentUser,
                    exception
            );
        }
    }

    private void failRouteEnvelope(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser,
            RuntimeException exception
    ) {
        persistPreProviderFailureQuietly(
                context,
                "MODEL_ROUTE_ENVELOPE_EXCEEDED",
                "MODEL_ROUTE_INPUT_ENVELOPE_EXCEEDED",
                currentUser,
                exception
        );

        throw new ChatTurnFailedException(
                context.chatId(),
                context.turnId(),
                context.clientRequestId(),
                "MODEL_ROUTE_INPUT_ENVELOPE_EXCEEDED",
                exception
        );
    }

    private void persistPreProviderFailureQuietly(
            ChatProcessingContext context,
            String providerErrorType,
            String failureCode,
            SafeAiUserPrincipal currentUser,
            RuntimeException primary
    ) {
        try {
            finalizationService.failBeforeProviderCall(
                    context,
                    providerErrorType,
                    failureCode,
                    currentUser
            );
        } catch (RuntimeException persistenceException) {
            primary.addSuppressed(
                    persistenceException
            );
        }
    }

    private void ensureOwnershipBeforeProvider(
            ChatProcessingContext context,
            ChatLockService.ChatLock redisLock,
            ChatTurnLeaseService.LeaseWatch leaseWatch,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            lockService.ensureValid(
                    redisLock
            );

            leaseService.ensureValid(
                    leaseWatch
            );
        } catch (ChatLockUnavailableException
                 | ChatStaleProcessorException exception) {
            markAmbiguousQuietly(
                    context,
                    null,
                    null,
                    "PROCESSING_OWNERSHIP_LOST_BEFORE_PROVIDER_CALL",
                    "CHAT_PROCESSING_OWNERSHIP_LOST_BEFORE_PROVIDER_CALL",
                    currentUser,
                    exception
            );

            throw ambiguous(
                    context,
                    exception
            );
        }
    }

    private void ensureOwnershipAfterProvider(
            ChatProcessingContext context,
            AiChatResponse response,
            ChatLockService.ChatLock redisLock,
            ChatTurnLeaseService.LeaseWatch leaseWatch,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            lockService.ensureValid(
                    redisLock
            );

            leaseService.ensureValid(
                    leaseWatch
            );
        } catch (ChatLockUnavailableException
                 | ChatStaleProcessorException exception) {
            markAmbiguousQuietly(
                    context,
                    response.requestedModel(),
                    response.providerRequestId(),
                    "PROCESSING_OWNERSHIP_LOST",
                    "CHAT_PROCESSING_OWNERSHIP_LOST",
                    currentUser,
                    exception
            );

            throw ambiguous(
                    context,
                    exception
            );
        }
    }

    private AiChatResponse invokeProvider(
            ChatProcessingContext context,
            SafeAiUserPrincipal currentUser
    ) {
        AiChatResponse response;

        try {
            response =
                    aiProvider.sendMessage(
                            context.aiRequest()
                    );
        } catch (AiProviderException exception) {
            if (exception.isOutcomeAmbiguous()) {
                markAmbiguousQuietly(
                        context,
                        exception.getModel(),
                        exception.getProviderRequestId(),
                        exception.getErrorType().name(),
                        "AI_PROVIDER_OUTCOME_AMBIGUOUS",
                        currentUser,
                        exception
                );

                throw ambiguous(
                        context,
                        exception
                );
            }

            try {
                finalizationService.failUnambiguous(
                        context,
                        exception,
                        currentUser
                );
            } catch (ChatStaleProcessorException staleProcessorException) {
                exception.addSuppressed(
                        staleProcessorException
                );

                throw ambiguous(
                        context,
                        exception
                );
            } catch (RuntimeException finalizationException) {
                exception.addSuppressed(
                        finalizationException
                );
            }

            throw new ChatTurnFailedException(
                    context.chatId(),
                    context.turnId(),
                    context.clientRequestId(),
                    "AI_PROVIDER_"
                            + exception.getErrorType().name(),
                    exception
            );
        } catch (RuntimeException unexpectedProviderException) {
            markAmbiguousQuietly(
                    context,
                    null,
                    null,
                    unexpectedProviderException
                            .getClass()
                            .getSimpleName(),
                    "AI_PROVIDER_UNCLASSIFIED_OUTCOME",
                    currentUser,
                    unexpectedProviderException
            );

            throw ambiguous(
                    context,
                    unexpectedProviderException
            );
        }

        if (response == null) {
            IllegalStateException nullResponse =
                    new IllegalStateException(
                            "AI provider вернул null response"
                    );

            markAmbiguousQuietly(
                    context,
                    null,
                    null,
                    "NULL_PROVIDER_RESPONSE",
                    "AI_PROVIDER_UNCLASSIFIED_OUTCOME",
                    currentUser,
                    nullResponse
            );

            throw ambiguous(
                    context,
                    nullResponse
            );
        }

        return response;
    }

    private RagCompletion completeRag(
            ChatProcessingContext context,
            RagPreparation preparation,
            AiChatResponse providerResponse,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            RagCompletion completion =
                    Objects.requireNonNull(
                            ragService.complete(
                                    preparation,
                                    providerResponse
                            ),
                            "ragCompletion не должен быть null"
                    );

            Objects.requireNonNull(
                    completion.response(),
                    "ragCompletion.response не должен быть null"
            );

            return completion;
        } catch (RuntimeException exception) {
            markAmbiguousQuietly(
                    context,
                    providerResponse.requestedModel(),
                    providerResponse.providerRequestId(),
                    exception.getClass().getSimpleName(),
                    "RAG_COMPLETION_FAILED_AFTER_PROVIDER_RESPONSE",
                    currentUser,
                    exception
            );

            throw ambiguous(
                    context,
                    exception
            );
        }
    }

    private SendMessageResponse persistSuccess(
            ChatProcessingContext context,
            RagCompletion completion,
            AiChatResponse response,
            SafeAiUserPrincipal currentUser
    ) {
        try {
            return finalizationService.succeedRag(
                    context,
                    completion,
                    currentUser
            );
        } catch (ChatStaleProcessorException exception) {
            throw ambiguous(
                    context,
                    exception
            );
        } catch (RuntimeException persistenceException) {
            try {
                finalizationService
                        .markAmbiguousAfterPersistenceFailure(
                                context,
                                response,
                                currentUser
                        );
            } catch (RuntimeException ambiguityPersistenceException) {
                persistenceException.addSuppressed(
                        ambiguityPersistenceException
                );
            }

            throw ambiguous(
                    context,
                    persistenceException
            );
        }
    }

    private void markAmbiguousQuietly(
            ChatProcessingContext context,
            String requestedModel,
            String providerRequestId,
            String providerErrorType,
            String failureCode,
            SafeAiUserPrincipal currentUser,
            RuntimeException primary
    ) {
        try {
            finalizationService.markAmbiguous(
                    context,
                    null,
                    requestedModel,
                    providerRequestId,
                    providerErrorType,
                    failureCode,
                    currentUser
            );
        } catch (RuntimeException persistenceException) {
            primary.addSuppressed(
                    persistenceException
            );

            log.error(
                    "Failed to persist AMBIGUOUS chat turn: turnId={}",
                    context.turnId(),
                    persistenceException
            );
        }
    }

    private static AiOutcomeAmbiguousException ambiguous(
            ChatProcessingContext context,
            RuntimeException cause
    ) {
        return new AiOutcomeAmbiguousException(
                context.chatId(),
                context.turnId(),
                context.clientRequestId(),
                cause
        );
    }
}
