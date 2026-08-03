package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=true",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
class ChatMigrationIntegrityIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Test
    void v32CreatesPersistentTurnAndQuotaLedger() {
        List<String> tables = jdbcTemplate.queryForList("""
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                  and table_name in ('chat_turns', 'chat_quota_reservations')
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "chat_quota_reservations",
                "chat_turns"
        );
    }

    @Test
    void providerOperationAndProviderRequestMetadataColumnsExist() {
        List<String> messageColumns = jdbcTemplate.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'chat_messages'
                  and column_name in ('requested_model', 'provider_request_id')
                order by column_name
                """, String.class);
        List<String> turnColumns = jdbcTemplate.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'chat_turns'
                  and column_name in (
                      'provider_operation_id',
                      'processing_token',
                      'lease_until',
                      'provider_call_started_at'
                  )
                order by column_name
                """, String.class);

        assertThat(messageColumns).containsExactly(
                "provider_request_id",
                "requested_model"
        );
        assertThat(turnColumns).containsExactly(
                "lease_until",
                "processing_token",
                "provider_call_started_at",
                "provider_operation_id"
        );
    }

    @Test
    void requiredProductionIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList("""
                select indexname
                from pg_indexes
                where schemaname = 'public'
                  and indexname in (
                      'uq_chat_turns_session_client_request',
                      'uq_chat_turns_provider_operation',
                      'ux_chat_turns_one_processing_per_session',
                      'ux_chat_messages_single_reply',
                      'idx_chat_turns_succeeded_history',
                      'idx_chat_turns_expired_processing',
                      'idx_chat_messages_visible_session',
                      'ux_chat_sessions_id_org_user',
                      'ux_chat_messages_id_session_org',
                      'ux_chat_messages_user_turn_identity',
                      'ux_chat_messages_assistant_turn_identity',
                      'ux_chat_turns_id_org_user'
                  )
                order by indexname
                """, String.class);

        assertThat(indexes).containsExactlyInAnyOrder(
                "uq_chat_turns_session_client_request",
                "uq_chat_turns_provider_operation",
                "ux_chat_turns_one_processing_per_session",
                "ux_chat_messages_single_reply",
                "idx_chat_turns_succeeded_history",
                "idx_chat_turns_expired_processing",
                "idx_chat_messages_visible_session",
                "ux_chat_sessions_id_org_user",
                "ux_chat_messages_id_session_org",
                "ux_chat_messages_user_turn_identity",
                "ux_chat_messages_assistant_turn_identity",
                "ux_chat_turns_id_org_user"
        );
    }

    @Test
    void idempotencyAndProviderOperationConstraintsAreUnique() {
        List<String> definitions = jdbcTemplate.queryForList("""
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conrelid = 'public.chat_turns'::regclass
                  and conname in (
                      'uq_chat_turns_session_client_request',
                      'uq_chat_turns_provider_operation'
                  )
                order by conname
                """, String.class);

        assertThat(definitions)
                .hasSize(2)
                .allMatch(definition -> definition.startsWith("UNIQUE"));
    }

    @Test
    void compositeTenantForeignKeysAreInstalledAndValidated() {
        List<String> constraints = jdbcTemplate.queryForList("""
                select conname
                from pg_constraint
                where convalidated
                  and conname in (
                      'fk_chat_turns_session_tenant_user',
                      'fk_chat_turns_user_message_identity',
                      'fk_chat_turns_assistant_message_identity',
                      'fk_chat_quota_turn_tenant_user',
                      'fk_chat_messages_reply_same_session_tenant'
                  )
                order by conname
                """, String.class);

        assertThat(constraints).containsExactlyInAnyOrder(
                "fk_chat_turns_session_tenant_user",
                "fk_chat_turns_user_message_identity",
                "fk_chat_turns_assistant_message_identity",
                "fk_chat_quota_turn_tenant_user",
                "fk_chat_messages_reply_same_session_tenant"
        );
    }

    @Test
    void oneSessionCannotHaveTwoProcessingTurns() {
        UUID firstClient = UUID.randomUUID();
        UUID firstUser = insertUserMessage(
                CHAT_ID, ORGANIZATION_ID, firstClient, "First", NOW
        );
        insertProcessingTurn(
                CHAT_ID, ORGANIZATION_ID, USER_ID, firstClient, firstUser,
                UUID.randomUUID(), NOW.plusSeconds(180), null
        );

        UUID secondClient = UUID.randomUUID();
        UUID secondUser = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                secondClient,
                "Second",
                NOW.plusSeconds(1)
        );

        assertThatThrownBy(() -> insertProcessingTurn(
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                secondClient,
                secondUser,
                UUID.randomUUID(),
                NOW.plusSeconds(180),
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondAssistantReplyToSameUserMessageIsRejected() {
        UUID client = UUID.randomUUID();
        UUID userMessage = insertUserMessage(
                CHAT_ID, ORGANIZATION_ID, client, "Question", NOW
        );
        insertCompletedAssistant(
                CHAT_ID,
                ORGANIZATION_ID,
                userMessage,
                "First answer",
                "COMPLETED",
                NOW.plusSeconds(1)
        );

        assertThatThrownBy(() -> insertCompletedAssistant(
                CHAT_ID,
                ORGANIZATION_ID,
                userMessage,
                "Second answer",
                "COMPLETED",
                NOW.plusSeconds(2)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void crossSessionReplyIsRejected() {
        UUID client = UUID.randomUUID();
        UUID foreignUserMessage = insertUserMessage(
                OTHER_CHAT_ID,
                OTHER_ORGANIZATION_ID,
                client,
                "Foreign question",
                NOW
        );

        assertThatThrownBy(() -> insertCompletedAssistant(
                CHAT_ID,
                ORGANIZATION_ID,
                foreignUserMessage,
                "Invalid answer",
                "COMPLETED",
                NOW.plusSeconds(1)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void replyToAssistantIsRejected() {
        UUID client = UUID.randomUUID();
        UUID userMessage = insertUserMessage(
                CHAT_ID, ORGANIZATION_ID, client, "Question", NOW
        );
        UUID assistant = insertCompletedAssistant(
                CHAT_ID,
                ORGANIZATION_ID,
                userMessage,
                "Answer",
                "COMPLETED",
                NOW.plusSeconds(1)
        );

        assertThatThrownBy(() -> insertCompletedAssistant(
                CHAT_ID,
                ORGANIZATION_ID,
                assistant,
                "Reply to assistant",
                "COMPLETED",
                NOW.plusSeconds(2)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chatTurnTenantMustMatchSessionAndUser() {
        UUID client = UUID.randomUUID();
        UUID userMessage = insertUserMessage(
                CHAT_ID, ORGANIZATION_ID, client, "Question", NOW
        );

        assertThatThrownBy(() -> insertProcessingTurn(
                CHAT_ID,
                OTHER_ORGANIZATION_ID,
                OTHER_USER_ID,
                client,
                userMessage,
                UUID.randomUUID(),
                NOW.plusSeconds(180),
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void quotaReservationTenantMustMatchTurn() {
        UUID client = UUID.randomUUID();
        UUID userMessage = insertUserMessage(
                CHAT_ID, ORGANIZATION_ID, client, "Question", NOW
        );
        UUID turn = insertProcessingTurn(
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                client,
                userMessage,
                UUID.randomUUID(),
                NOW.plusSeconds(180),
                null
        );

        assertThatThrownBy(() -> insertQuotaReservation(
                turn,
                OTHER_ORGANIZATION_ID,
                OTHER_USER_ID
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
