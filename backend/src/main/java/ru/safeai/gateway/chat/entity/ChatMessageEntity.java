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
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

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

    /**
     * Идентификатор запроса, присланный frontend.
     * Заполняется только для USER-сообщения и используется
     * для идемпотентной повторной отправки.
     */
    @Column(name = "client_request_id")
    private UUID clientRequestId;

    /**
     * Идентификатор USER-сообщения, на которое отвечает ASSISTANT.
     * Заполняется только для сообщений ASSISTANT.
     */
    @Column(name = "reply_to_message_id")
    private UUID replyToMessageId;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

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

    @Column(name = "cost_usd", precision = 12, scale = 6)
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ChatMessageStatus status;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        if (organization == null && session != null) {
            organization = session.getOrganization();
        }

        if (createdAt == null) {
            createdAt = Instant.now();
        }

        if (status == null) {
            status = ChatMessageStatus.COMPLETED;
        }

        if (currency != null) {
            currency = currency
                    .trim()
                    .toUpperCase(Locale.ROOT);
        }

        boolean completedAssistant =
                role == ChatMessageRole.ASSISTANT
                        && status == ChatMessageStatus.COMPLETED;

        if (!completedAssistant) {
            clearAiMetadata();
            return;
        }

        if (usageStatus == null) {
            usageStatus = AiChatResponse.determineUsageStatus(
                    inputTokens,
                    outputTokens
            );
        }

        if (pricingStatus == null) {
            pricingStatus = PricingStatus.UNPRICED;
        }
    }

    private void clearAiMetadata() {
        model = null;
        providerMessageId = null;
        aiResponseStatus = null;
        finishReason = null;

        inputTokens = null;
        outputTokens = null;
        usageStatus = UsageStatus.NOT_APPLICABLE;

        costUsd = null;
        pricingStatus = PricingStatus.NOT_APPLICABLE;
        currency = null;
        pricingVersion = null;
        pricingCalculatedAt = null;
    }
}