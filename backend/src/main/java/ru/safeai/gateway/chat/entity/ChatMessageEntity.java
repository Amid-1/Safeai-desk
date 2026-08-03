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
import lombok.Getter;
import lombok.Setter;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.organization.entity.OrganizationEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

    private static final int MAX_MODEL_CHARS = 100;
    private static final int MAX_PROVIDER_ID_CHARS = 255;
    private static final int MAX_FINISH_REASON_CHARS = 100;
    private static final int MAX_PRICING_VERSION_CHARS = 64;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private OrganizationEntity organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 50)
    private ChatMessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "client_request_id")
    private UUID clientRequestId;

    @Column(name = "reply_to_message_id")
    private UUID replyToMessageId;

    @Column(name = "requested_model", length = 100)
    private String requestedModel;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "provider_request_id", length = 255)
    private String providerRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_response_status", length = 32)
    private AiResponseStatus aiResponseStatus;

    @Column(name = "finish_reason", length = 100)
    private String finishReason;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_status", nullable = false, length = 32)
    private UsageStatus usageStatus;

    @Column(name = "cost_usd", precision = 30, scale = 12)
    private BigDecimal costUsd;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_status", nullable = false, length = 32)
    private PricingStatus pricingStatus;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "pricing_version", length = 64)
    private String pricingVersion;

    @Column(name = "pricing_calculated_at")
    private Instant pricingCalculatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ChatMessageStatus status;

    public static ChatMessageEntity user(
            ChatSessionEntity session,
            String content,
            UUID clientRequestId,
            Instant now
    ) {
        ChatMessageEntity message = base(
                session,
                ChatMessageRole.USER,
                ChatMessageStatus.COMPLETED,
                content,
                now
        );
        message.clientRequestId = Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );
        message.usageStatus = UsageStatus.NOT_APPLICABLE;
        message.pricingStatus = PricingStatus.NOT_APPLICABLE;
        message.validateInvariant();
        return message;
    }

    public static ChatMessageEntity completedAssistant(
            UUID id,
            ChatSessionEntity session,
            UUID userMessageId,
            AiChatResponse response,
            Instant now
    ) {
        Objects.requireNonNull(response, "response не должен быть null");
        ChatMessageEntity message = base(
                session,
                ChatMessageRole.ASSISTANT,
                ChatMessageStatus.COMPLETED,
                response.content(),
                now
        );
        message.id = Objects.requireNonNull(id, "id не должен быть null");
        message.replyToMessageId = Objects.requireNonNull(
                userMessageId,
                "userMessageId не должен быть null"
        );
        message.requestedModel = response.requestedModel();
        message.model = response.model();
        message.providerMessageId = response.providerMessageId();
        message.providerRequestId = response.providerRequestId();
        message.aiResponseStatus = response.responseStatus();
        message.finishReason = response.finishReason();
        message.inputTokens = response.inputTokens();
        message.outputTokens = response.outputTokens();
        message.usageStatus = response.usageStatus();
        message.costUsd = response.costUsd();
        message.pricingStatus = response.pricingStatus();
        message.currency = response.currency();
        message.pricingVersion = response.priceVersion();
        message.pricingCalculatedAt = response.pricingCalculatedAt();
        message.validateInvariant();
        return message;
    }

    public static ChatMessageEntity system(
            ChatSessionEntity session,
            String content,
            Instant now
    ) {
        ChatMessageEntity message = base(
                session,
                ChatMessageRole.SYSTEM,
                ChatMessageStatus.COMPLETED,
                content,
                now
        );
        message.usageStatus = UsageStatus.NOT_APPLICABLE;
        message.pricingStatus = PricingStatus.NOT_APPLICABLE;
        message.validateInvariant();
        return message;
    }

    private static ChatMessageEntity base(
            ChatSessionEntity session,
            ChatMessageRole role,
            ChatMessageStatus status,
            String content,
            Instant now
    ) {
        Objects.requireNonNull(session, "session не должен быть null");
        ChatMessageEntity message = new ChatMessageEntity();
        message.id = UUID.randomUUID();
        message.session = session;
        message.organization = Objects.requireNonNull(
                session.getOrganization(),
                "session.organization не должен быть null"
        );
        message.role = Objects.requireNonNull(role, "role не должен быть null");
        message.status = Objects.requireNonNull(
                status,
                "status не должен быть null"
        );
        message.content = requireContent(content);
        message.createdAt = Objects.requireNonNull(now, "now не должен быть null");
        return message;
    }

    @PrePersist
    @PreUpdate
    void validateInvariant() {
        Objects.requireNonNull(id, "message id не должен быть null");
        Objects.requireNonNull(session, "session не должен быть null");
        Objects.requireNonNull(
                organization,
                "organization не должен быть null"
        );
        Objects.requireNonNull(role, "role не должен быть null");
        Objects.requireNonNull(status, "status не должен быть null");
        Objects.requireNonNull(createdAt, "createdAt не должен быть null");
        content = requireContent(content);

        requestedModel = normalizeOptional(
                requestedModel,
                MAX_MODEL_CHARS,
                "requestedModel"
        );
        model = normalizeOptional(model, MAX_MODEL_CHARS, "model");
        providerMessageId = normalizeOptional(
                providerMessageId,
                MAX_PROVIDER_ID_CHARS,
                "providerMessageId"
        );
        providerRequestId = normalizeOptional(
                providerRequestId,
                MAX_PROVIDER_ID_CHARS,
                "providerRequestId"
        );
        finishReason = normalizeOptional(
                finishReason,
                MAX_FINISH_REASON_CHARS,
                "finishReason"
        );
        pricingVersion = normalizeOptional(
                pricingVersion,
                MAX_PRICING_VERSION_CHARS,
                "pricingVersion"
        );
        currency = normalizeCurrency(currency);

        if (session.getOrganization() != null
                && session.getOrganization().getId() != null
                && organization.getId() != null
                && !session.getOrganization().getId().equals(organization.getId())) {
            throw new IllegalStateException(
                    "message organization не совпадает с session organization"
            );
        }

        switch (role) {
            case USER -> validateUser();
            case ASSISTANT -> validateAssistant();
            case SYSTEM -> validateSystem();
        }
    }

    private void validateUser() {
        if (status != ChatMessageStatus.COMPLETED) {
            throw new IllegalStateException(
                    "USER message должен иметь статус COMPLETED"
            );
        }
        Objects.requireNonNull(
                clientRequestId,
                "USER message требует clientRequestId"
        );
        if (replyToMessageId != null) {
            throw new IllegalStateException(
                    "USER message не может иметь replyToMessageId"
            );
        }
        requireNoAiMetadata("USER");
    }

    private void validateAssistant() {
        if (clientRequestId != null) {
            throw new IllegalStateException(
                    "ASSISTANT message не может иметь clientRequestId"
            );
        }
        Objects.requireNonNull(
                replyToMessageId,
                "ASSISTANT message требует replyToMessageId"
        );
        if (status != ChatMessageStatus.COMPLETED) {
            requireNoAiMetadata("non-completed ASSISTANT");
            return;
        }

        requireNonBlank(requestedModel, "requestedModel");
        requireNonBlank(model, "model");
        Objects.requireNonNull(
                aiResponseStatus,
                "completed ASSISTANT требует aiResponseStatus"
        );
        validateToken(inputTokens, "inputTokens");
        validateToken(outputTokens, "outputTokens");
        Objects.requireNonNull(
                usageStatus,
                "completed ASSISTANT требует usageStatus"
        );
        Objects.requireNonNull(
                pricingStatus,
                "completed ASSISTANT требует pricingStatus"
        );
        validateUsage();
        validatePricing();
    }

    private void validateSystem() {
        if (clientRequestId != null || replyToMessageId != null) {
            throw new IllegalStateException(
                    "SYSTEM message не может иметь idempotency/reply metadata"
            );
        }
        requireNoAiMetadata("SYSTEM");
    }

    private void requireNoAiMetadata(String kind) {
        boolean aiMetadataPresent = requestedModel != null
                || model != null
                || providerMessageId != null
                || providerRequestId != null
                || aiResponseStatus != null
                || finishReason != null
                || inputTokens != null
                || outputTokens != null
                || costUsd != null
                || currency != null
                || pricingVersion != null
                || pricingCalculatedAt != null;
        if (aiMetadataPresent) {
            throw new IllegalStateException(
                    kind + " message не может содержать AI metadata"
            );
        }
        if (usageStatus != UsageStatus.NOT_APPLICABLE
                || pricingStatus != PricingStatus.NOT_APPLICABLE) {
            throw new IllegalStateException(
                    kind + " message требует NOT_APPLICABLE statuses"
            );
        }
    }

    private void validateUsage() {
        switch (usageStatus) {
            case AVAILABLE -> {
                if (inputTokens == null || outputTokens == null) {
                    throw new IllegalStateException(
                            "AVAILABLE требует оба token counter"
                    );
                }
            }
            case PARTIAL -> {
                if ((inputTokens == null) == (outputTokens == null)) {
                    throw new IllegalStateException(
                            "PARTIAL требует ровно один token counter"
                    );
                }
            }
            case MISSING -> {
                if (inputTokens != null || outputTokens != null) {
                    throw new IllegalStateException(
                            "MISSING требует отсутствующие token counters"
                    );
                }
            }
            case NOT_APPLICABLE -> throw new IllegalStateException(
                    "completed ASSISTANT не может иметь NOT_APPLICABLE usage"
            );
        }
    }

    private void validatePricing() {
        if (costUsd != null
                && (costUsd.signum() < 0 || costUsd.scale() > 12)) {
            throw new IllegalStateException(
                    "costUsd должен быть >= 0 и иметь scale <= 12"
            );
        }
        switch (pricingStatus) {
            case PRICED -> {
                if (costUsd == null || costUsd.signum() <= 0) {
                    throw new IllegalStateException(
                            "PRICED требует положительный costUsd"
                    );
                }
                requireKnownPricingMetadata();
            }
            case FREE -> {
                if (costUsd == null || costUsd.signum() != 0) {
                    throw new IllegalStateException(
                            "FREE требует costUsd = 0"
                    );
                }
                requireKnownPricingMetadata();
            }
            case UNPRICED, CALCULATION_FAILED -> {
                if (costUsd != null || currency != null || pricingVersion != null) {
                    throw new IllegalStateException(
                            pricingStatus + " не может содержать known cost metadata"
                    );
                }
                Objects.requireNonNull(
                        pricingCalculatedAt,
                        pricingStatus + " требует pricingCalculatedAt"
                );
            }
            case NOT_APPLICABLE -> throw new IllegalStateException(
                    "completed ASSISTANT не может иметь NOT_APPLICABLE pricing"
            );
        }
    }

    private void requireKnownPricingMetadata() {
        if (!"USD".equals(currency)) {
            throw new IllegalStateException(
                    "В текущей версии поддерживается только USD"
            );
        }
        requireNonBlank(pricingVersion, "pricingVersion");
        Objects.requireNonNull(
                pricingCalculatedAt,
                "pricingCalculatedAt не должен быть null"
        );
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "message content не должен быть пустым"
            );
        }
        return value;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " не должен быть пустым");
        }
    }

    private static void validateToken(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalStateException(field + " не может быть отрицательным");
        }
    }

    private static String normalizeOptional(
            String value,
            int maxLength,
            String field
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " не должен превышать " + maxLength + " символов"
            );
        }
        return normalized;
    }

    private static String normalizeCurrency(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "currency должна соответствовать [A-Z]{3}"
            );
        }
        return normalized;
    }
}
