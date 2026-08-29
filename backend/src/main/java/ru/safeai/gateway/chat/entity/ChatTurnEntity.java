package ru.safeai.gateway.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "chat_turns")
public class ChatTurnEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private OrganizationEntity organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "request_content_hash", nullable = false, length = 64)
    private String requestContentHash;

    @Column(name = "provider_operation_id", nullable = false, unique = true)
    private UUID providerOperationId;

    @Column(name = "user_message_id", nullable = false, unique = true)
    private UUID userMessageId;

    @Column(name = "assistant_message_id", unique = true)
    private UUID assistantMessageId;

    /**
     * Exact immutable V45 governance decision.
     * Null допускается только для historical V1-V44 rows и их replay.
     */
    @Column(name = "model_route_decision_id", unique = true)
    private UUID modelRouteDecisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ChatTurnState state;

    @Column(name = "processing_token")
    private UUID processingToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    /**
     * Persisted immediately before the first provider HTTP attempt.
     */
    @Column(name = "provider_call_started_at")
    private Instant providerCallStartedAt;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "requested_model", length = 100)
    private String requestedModel;

    @Column(name = "resolved_model", length = 100)
    private String resolvedModel;

    @Column(name = "provider_request_id", length = 255)
    private String providerRequestId;

    @Column(name = "provider_error_type", length = 64)
    private String providerErrorType;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "outcome_ambiguous", nullable = false)
    private boolean outcomeAmbiguous;

    @Column(name = "knowledge_base_id")
    private UUID knowledgeBaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_mode", nullable = false, length = 32)
    private KnowledgeMode knowledgeMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Legacy V44-compatible factory without requested model,
     * route-decision evidence and knowledge scope.
     */
    public static ChatTurnEntity processing(
            UUID id,
            ChatSessionEntity session,
            UUID clientRequestId,
            String requestContentHash,
            UUID providerOperationId,
            UUID userMessageId,
            UUID processingToken,
            Instant now,
            Instant leaseUntil,
            String provider
    ) {
        return processing(
                id,
                session,
                clientRequestId,
                requestContentHash,
                providerOperationId,
                userMessageId,
                processingToken,
                now,
                leaseUntil,
                provider,
                null,
                null,
                null,
                KnowledgeMode.GENERAL
        );
    }

    /**
     * Source compatibility for V44 tests/fixtures.
     * Production V45 reservation code uses the overload with
     * requestedModel and modelRouteDecisionId.
     */
    @Deprecated
    public static ChatTurnEntity processing(
            UUID id,
            ChatSessionEntity session,
            UUID clientRequestId,
            String requestContentHash,
            UUID providerOperationId,
            UUID userMessageId,
            UUID processingToken,
            Instant now,
            Instant leaseUntil,
            String provider,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        return processing(
                id,
                session,
                clientRequestId,
                requestContentHash,
                providerOperationId,
                userMessageId,
                processingToken,
                now,
                leaseUntil,
                provider,
                null,
                null,
                knowledgeBaseId,
                knowledgeMode
        );
    }

    /**
     * Production V45 factory bound to one immutable route decision.
     */
    public static ChatTurnEntity processing(
            UUID id,
            ChatSessionEntity session,
            UUID clientRequestId,
            String requestContentHash,
            UUID providerOperationId,
            UUID userMessageId,
            UUID processingToken,
            Instant now,
            Instant leaseUntil,
            String provider,
            String requestedModel,
            UUID modelRouteDecisionId,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        Objects.requireNonNull(
                session,
                "session не должен быть null"
        );

        ChatTurnEntity turn =
                new ChatTurnEntity();

        turn.id =
                Objects.requireNonNull(
                        id,
                        "id не должен быть null"
                );

        turn.session =
                session;

        turn.organization =
                Objects.requireNonNull(
                        session.getOrganization(),
                        "session.organization не должен быть null"
                );

        turn.user =
                Objects.requireNonNull(
                        session.getUser(),
                        "session.user не должен быть null"
                );

        turn.clientRequestId =
                Objects.requireNonNull(
                        clientRequestId,
                        "clientRequestId не должен быть null"
                );

        turn.requestContentHash =
                requestContentHash;

        turn.providerOperationId =
                Objects.requireNonNull(
                        providerOperationId,
                        "providerOperationId не должен быть null"
                );

        turn.userMessageId =
                Objects.requireNonNull(
                        userMessageId,
                        "userMessageId не должен быть null"
                );

        turn.state =
                ChatTurnState.PROCESSING;

        turn.processingToken =
                Objects.requireNonNull(
                        processingToken,
                        "processingToken не должен быть null"
                );

        turn.leaseUntil =
                Objects.requireNonNull(
                        leaseUntil,
                        "leaseUntil не должен быть null"
                );

        turn.provider =
                normalize(
                        provider,
                        32,
                        "provider"
                );

        turn.requestedModel =
                normalize(
                        requestedModel,
                        100,
                        "requestedModel"
                );

        turn.modelRouteDecisionId =
                modelRouteDecisionId;

        if (modelRouteDecisionId != null) {
            requireNonBlank(
                    turn.requestedModel,
                    "V45 ChatTurn с modelRouteDecisionId требует requestedModel"
            );
        }

        turn.knowledgeBaseId =
                knowledgeBaseId;

        turn.knowledgeMode =
                knowledgeMode == null
                        ? KnowledgeMode.GENERAL
                        : knowledgeMode;

        turn.createdAt =
                Objects.requireNonNull(
                        now,
                        "now не должен быть null"
                );

        turn.updatedAt =
                now;

        turn.validateInvariant();

        return turn;
    }

    @PrePersist
    @PreUpdate
    void validateInvariant() {
        Objects.requireNonNull(
                id,
                "turn id не должен быть null"
        );

        Objects.requireNonNull(
                session,
                "session не должен быть null"
        );

        Objects.requireNonNull(
                organization,
                "organization не должен быть null"
        );

        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );

        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );

        Objects.requireNonNull(
                providerOperationId,
                "providerOperationId не должен быть null"
        );

        Objects.requireNonNull(
                userMessageId,
                "userMessageId не должен быть null"
        );

        Objects.requireNonNull(
                state,
                "state не должен быть null"
        );

        Objects.requireNonNull(
                createdAt,
                "createdAt не должен быть null"
        );

        Objects.requireNonNull(
                updatedAt,
                "updatedAt не должен быть null"
        );

        Objects.requireNonNull(
                knowledgeMode,
                "knowledgeMode не должен быть null"
        );

        validateKnowledgeScope();

        if (
                requestContentHash == null
                        || !requestContentHash.matches("[0-9a-f]{64}")
        ) {
            throw new IllegalStateException(
                    "requestContentHash должен быть lowercase SHA-256"
            );
        }

        provider =
                normalize(
                        provider,
                        32,
                        "provider"
                );

        requestedModel =
                normalize(
                        requestedModel,
                        100,
                        "requestedModel"
                );

        resolvedModel =
                normalize(
                        resolvedModel,
                        100,
                        "resolvedModel"
                );

        providerRequestId =
                normalize(
                        providerRequestId,
                        255,
                        "providerRequestId"
                );

        providerErrorType =
                normalize(
                        providerErrorType,
                        64,
                        "providerErrorType"
                );

        failureCode =
                normalize(
                        failureCode,
                        64,
                        "failureCode"
                );

        if (modelRouteDecisionId != null) {
            requireNonBlank(
                    provider,
                    "V45 ChatTurn с modelRouteDecisionId требует provider"
            );

            requireNonBlank(
                    requestedModel,
                    "V45 ChatTurn с modelRouteDecisionId требует requestedModel"
            );
        }

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalStateException(
                    "updatedAt не может быть раньше createdAt"
            );
        }

        if (
                providerCallStartedAt != null
                        && providerCallStartedAt.isBefore(createdAt)
        ) {
            throw new IllegalStateException(
                    "providerCallStartedAt не может быть раньше createdAt"
            );
        }

        if (
                completedAt != null
                        && providerCallStartedAt != null
                        && providerCallStartedAt.isAfter(completedAt)
        ) {
            throw new IllegalStateException(
                    "providerCallStartedAt не может быть позже completedAt"
            );
        }

        switch (state) {
            case NEW -> {
                if (
                        processingToken != null
                                || leaseUntil != null
                ) {
                    throw new IllegalStateException(
                            "NEW turn не может иметь processing lease"
                    );
                }

                requireNonTerminal();
            }

            case PROCESSING -> {
                Objects.requireNonNull(
                        processingToken,
                        "PROCESSING требует processingToken"
                );

                Objects.requireNonNull(
                        leaseUntil,
                        "PROCESSING требует leaseUntil"
                );

                requireNonBlank(
                        provider,
                        "PROCESSING требует provider"
                );

                if (!leaseUntil.isAfter(updatedAt)) {
                    throw new IllegalStateException(
                            "PROCESSING leaseUntil должен быть позже updatedAt"
                    );
                }

                requireNonTerminal();
            }

            case SUCCEEDED -> {
                Objects.requireNonNull(
                        assistantMessageId,
                        "SUCCEEDED требует assistantMessageId"
                );

                Objects.requireNonNull(
                        providerCallStartedAt,
                        "SUCCEEDED требует providerCallStartedAt"
                );

                requireNonBlank(
                        provider,
                        "SUCCEEDED требует provider"
                );

                requireNonBlank(
                        requestedModel,
                        "SUCCEEDED требует requestedModel"
                );

                requireNonBlank(
                        resolvedModel,
                        "SUCCEEDED требует resolvedModel"
                );

                requireTerminal(
                        false,
                        true
                );
            }

            case FAILED -> {
                requireNonBlank(
                        failureCode,
                        "FAILED требует failureCode"
                );

                requireTerminal(
                        false,
                        false
                );
            }

            case AMBIGUOUS -> {
                Objects.requireNonNull(
                        providerCallStartedAt,
                        "AMBIGUOUS требует providerCallStartedAt"
                );

                requireNonBlank(
                        failureCode,
                        "AMBIGUOUS требует failureCode"
                );

                requireTerminal(
                        true,
                        false
                );
            }
        }
    }

    private void validateKnowledgeScope() {
        boolean usesKnowledge =
                knowledgeMode.usesKnowledge();

        if (
                usesKnowledge
                        && knowledgeBaseId == null
        ) {
            throw new IllegalStateException(
                    "Knowledge mode требует knowledgeBaseId"
            );
        }

        if (
                !usesKnowledge
                        && knowledgeBaseId != null
        ) {
            throw new IllegalStateException(
                    "GENERAL mode не может содержать knowledgeBaseId"
            );
        }
    }

    private void requireNonTerminal() {
        if (
                assistantMessageId != null
                        || completedAt != null
                        || outcomeAmbiguous
        ) {
            throw new IllegalStateException(
                    "non-terminal turn содержит terminal metadata"
            );
        }
    }

    private void requireTerminal(
            boolean ambiguous,
            boolean assistantRequired
    ) {
        if (
                processingToken != null
                        || leaseUntil != null
        ) {
            throw new IllegalStateException(
                    "terminal turn не может иметь processing lease"
            );
        }

        Objects.requireNonNull(
                completedAt,
                "terminal turn требует completedAt"
        );

        if (completedAt.isBefore(createdAt)) {
            throw new IllegalStateException(
                    "completedAt не может быть раньше createdAt"
            );
        }

        if (
                assistantRequired
                        && assistantMessageId == null
        ) {
            throw new IllegalStateException(
                    "SUCCEEDED требует assistantMessageId"
            );
        }

        if (
                !assistantRequired
                        && assistantMessageId != null
        ) {
            throw new IllegalStateException(
                    "FAILED/AMBIGUOUS не могут иметь assistantMessageId"
            );
        }

        if (outcomeAmbiguous != ambiguous) {
            throw new IllegalStateException(
                    "outcomeAmbiguous не согласован с state"
            );
        }
    }

    private static void requireNonBlank(
            String value,
            String message
    ) {
        if (
                value == null
                        || value.isBlank()
        ) {
            throw new IllegalStateException(
                    message
            );
        }
    }

    private static String normalize(
            String value,
            int maxLength,
            String field
    ) {
        if (value == null) {
            return null;
        }

        String normalized =
                value.trim();

        if (normalized.isEmpty()) {
            return null;
        }

        if (
                normalized.length()
                        <= maxLength
        ) {
            return normalized;
        }

        throw new IllegalArgumentException(
                field
                        + " не должен превышать "
                        + maxLength
                        + " символов"
        );
    }
}
