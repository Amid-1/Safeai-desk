package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.BeforeEach;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public abstract class AbstractChatPostgresIntegrationTest
        extends AbstractPostgresIntegrationTest {

    protected static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    protected static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    protected static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    protected static final UUID OTHER_USER_ID =
            UUID.fromString(
                    "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
            );

    protected static final UUID CHAT_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    protected static final UUID OTHER_CHAT_ID =
            UUID.fromString(
                    "ffffffff-ffff-ffff-ffff-ffffffffffff"
            );

    protected static final Instant NOW =
            Instant.parse(
                    "2026-06-12T12:00:00Z"
            );

    @BeforeEach
    void prepareChatFixture() {
        jdbcTemplate.execute(
                """
                truncate table
                    public.chat_quota_reservations,
                    public.chat_turns,
                    public.usage_daily_quality_rollups
                cascade
                """
        );

        insertOrganization(
                ORGANIZATION_ID,
                "Chat Organization",
                true
        );

        insertOrganization(
                OTHER_ORGANIZATION_ID,
                "Other Organization",
                true
        );

        insertUser(
                USER_ID,
                ORGANIZATION_ID,
                "chat-user@test.example",
                true,
                "USER",
                NOW.minusSeconds(3_600)
        );

        insertUser(
                OTHER_USER_ID,
                OTHER_ORGANIZATION_ID,
                "other-user@test.example",
                true,
                "USER",
                NOW.minusSeconds(3_600)
        );

        insertChatSession(
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        );

        insertChatSession(
                OTHER_CHAT_ID,
                OTHER_USER_ID,
                OTHER_ORGANIZATION_ID
        );
    }

    protected UUID insertUserMessage(
            UUID sessionId,
            UUID organizationId,
            UUID clientRequestId,
            String content,
            Instant createdAt
    ) {
        UUID messageId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                insert into public.chat_messages (
                    id,
                    session_id,
                    organization_id,
                    role,
                    content,
                    client_request_id,
                    created_at,
                    status,
                    usage_status,
                    pricing_status
                ) values (
                    ?,
                    ?,
                    ?,
                    'USER',
                    ?,
                    ?,
                    ?,
                    'COMPLETED',
                    'NOT_APPLICABLE',
                    'NOT_APPLICABLE'
                )
                """,
                messageId,
                sessionId,
                organizationId,
                content,
                clientRequestId,
                Timestamp.from(createdAt)
        );

        return messageId;
    }

    protected UUID insertCompletedAssistant(
            UUID sessionId,
            UUID organizationId,
            UUID userMessageId,
            String content,
            String responseStatus,
            Instant createdAt
    ) {
        UUID messageId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                insert into public.chat_messages (
                    id,
                    session_id,
                    organization_id,
                    role,
                    content,
                    reply_to_message_id,
                    requested_model,
                    model,
                    provider_message_id,
                    provider_request_id,
                    ai_response_status,
                    finish_reason,
                    input_tokens,
                    output_tokens,
                    usage_status,
                    cost_usd,
                    pricing_status,
                    currency,
                    pricing_version,
                    pricing_calculated_at,
                    created_at,
                    status
                ) values (
                    ?,
                    ?,
                    ?,
                    'ASSISTANT',
                    ?,
                    ?,
                    'mock-safeai',
                    'mock-safeai',
                    ?,
                    ?,
                    ?,
                    'completed',
                    10,
                    20,
                    'AVAILABLE',
                    0,
                    'FREE',
                    'USD',
                    'mock-2026-01',
                    ?,
                    ?,
                    'COMPLETED'
                )
                """,
                messageId,
                sessionId,
                organizationId,
                content,
                userMessageId,
                "provider-message-" + messageId,
                "provider-request-" + messageId,
                responseStatus,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

        return messageId;
    }

    protected UUID insertProcessingTurn(
            UUID sessionId,
            UUID organizationId,
            UUID userId,
            UUID clientRequestId,
            UUID userMessageId,
            UUID processingToken,
            Instant leaseUntil,
            Instant providerCallStartedAt
    ) {
        UUID turnId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                insert into public.chat_turns (
                    id,
                    session_id,
                    organization_id,
                    user_id,
                    client_request_id,
                    request_content_hash,
                    provider_operation_id,
                    user_message_id,
                    state,
                    processing_token,
                    lease_until,
                    provider_call_started_at,
                    provider,
                    outcome_ambiguous,
                    created_at,
                    updated_at,
                    version
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'PROCESSING',
                    ?,
                    ?,
                    ?,
                    'mock',
                    false,
                    ?,
                    ?,
                    0
                )
                """,
                turnId,
                sessionId,
                organizationId,
                userId,
                clientRequestId,
                "0".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                processingToken,
                Timestamp.from(leaseUntil),
                timestamp(providerCallStartedAt),
                Timestamp.from(
                        NOW.minusSeconds(10)
                ),
                Timestamp.from(
                        NOW.minusSeconds(10)
                )
        );

        return turnId;
    }

    protected void insertQuotaReservation(
            UUID turnId,
            UUID organizationId,
            UUID userId
    ) {
        jdbcTemplate.update(
                """
                insert into public.chat_quota_reservations (
                    turn_id,
                    organization_id,
                    user_id,
                    period_start,
                    state,
                    reserved_input_tokens,
                    reserved_output_tokens,
                    reserved_cost_usd,
                    created_at,
                    updated_at
                ) values (
                    ?,
                    ?,
                    ?,
                    ?,
                    'RESERVED',
                    16000,
                    2048,
                    1.000000000000,
                    ?,
                    ?
                )
                """,
                turnId,
                organizationId,
                userId,
                java.sql.Date.valueOf(
                        LocalDate.of(
                                2026,
                                6,
                                1
                        )
                ),
                Timestamp.from(
                        NOW.minusSeconds(10)
                ),
                Timestamp.from(
                        NOW.minusSeconds(10)
                )
        );
    }

    protected static Timestamp timestamp(
            Instant value
    ) {
        return value == null
                ? null
                : Timestamp.from(value);
    }
}
