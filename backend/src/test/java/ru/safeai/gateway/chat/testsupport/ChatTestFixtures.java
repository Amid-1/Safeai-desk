package ru.safeai.gateway.chat.testsupport;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.chat.entity.ChatSessionEntity;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class ChatTestFixtures {

    public static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
            );

    public static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1"
            );

    public static final UUID CHAT_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-ccccccccccc1"
            );

    public static final UUID OTHER_CHAT_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-ccccccccccc2"
            );

    public static final UUID TURN_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-ddddddddddd1"
            );

    public static final UUID USER_MESSAGE_ID =
            UUID.fromString(
                    "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1"
            );

    public static final UUID ASSISTANT_MESSAGE_ID =
            UUID.fromString(
                    "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2"
            );

    public static final UUID CLIENT_REQUEST_ID =
            UUID.fromString(
                    "ffffffff-ffff-ffff-ffff-fffffffffff1"
            );

    public static final UUID PROVIDER_OPERATION_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    public static final UUID PROCESSING_TOKEN =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    public static final Instant NOW =
            Instant.parse("2026-08-02T09:00:00Z");

    public static final Clock CLOCK =
            Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            );

    private ChatTestFixtures() {
    }

    public static OrganizationEntity organization() {
        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setId(
                ORGANIZATION_ID
        );

        organization.setName(
                "Chat Organization"
        );

        organization.setEnabled(
                true
        );

        organization.setCreatedAt(
                NOW.minusSeconds(3600)
        );

        organization.setUpdatedAt(
                NOW.minusSeconds(3600)
        );

        return organization;
    }

    public static UserEntity user() {
        UserEntity user =
                new UserEntity();

        user.setId(
                USER_ID
        );

        user.setEmail(
                "chat-user@test.com"
        );

        user.setFullName(
                "Chat User"
        );

        user.setPasswordHash(
                "encoded"
        );

        user.setEnabled(
                true
        );

        user.setTokenVersion(
                0L
        );

        user.setOrganization(
                organization()
        );

        return user;
    }

    public static ChatSessionEntity session() {
        ChatSessionEntity session =
                ChatSessionEntity.create(
                        user(),
                        "Chat",
                        NOW.minusSeconds(120)
                );

        session.setId(
                CHAT_ID
        );

        return session;
    }

    public static SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "chat-user@test.com",
                0L,
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER"
                        )
                )
        );
    }

    public static AiChatResponse freeResponse() {
        return response(
                AiResponseStatus.COMPLETED,
                UsageStatus.AVAILABLE,
                PricingStatus.FREE,
                10,
                20,
                new BigDecimal("0.000000000000")
        );
    }

    public static AiChatResponse pricedResponse() {
        return response(
                AiResponseStatus.COMPLETED,
                UsageStatus.AVAILABLE,
                PricingStatus.PRICED,
                100,
                200,
                new BigDecimal("0.012345678901")
        );
    }

    public static AiChatResponse unpricedResponse() {
        return new AiChatResponse(
                "Answer",
                "requested-model",
                "resolved-model",
                "provider-message-id",
                "provider-request-id",
                AiResponseStatus.COMPLETED,
                "stop",
                10,
                20,
                UsageStatus.AVAILABLE,
                null,
                PricingStatus.UNPRICED,
                null,
                null,
                NOW
        );
    }

    public static AiChatResponse refusedResponse() {
        return response(
                AiResponseStatus.REFUSED,
                UsageStatus.AVAILABLE,
                PricingStatus.PRICED,
                7,
                3,
                new BigDecimal("0.001000000000")
        );
    }

    public static AiChatResponse incompleteResponse() {
        return response(
                AiResponseStatus.INCOMPLETE,
                UsageStatus.PARTIAL,
                PricingStatus.UNPRICED,
                7,
                null,
                null
        );
    }

    private static AiChatResponse response(
            AiResponseStatus responseStatus,
            UsageStatus usageStatus,
            PricingStatus pricingStatus,
            Integer inputTokens,
            Integer outputTokens,
            BigDecimal costUsd
    ) {
        boolean knownPrice =
                pricingStatus == PricingStatus.PRICED
                        || pricingStatus == PricingStatus.FREE;

        return new AiChatResponse(
                "Answer",
                "requested-model",
                "resolved-model",
                "provider-message-id",
                "provider-request-id",
                responseStatus,
                responseStatus == AiResponseStatus.REFUSED
                        ? "content_filter"
                        : "stop",
                inputTokens,
                outputTokens,
                usageStatus,
                costUsd,
                pricingStatus,
                knownPrice
                        ? "USD"
                        : null,
                knownPrice
                        ? "pricing-v1"
                        : null,
                NOW
        );
    }
}
