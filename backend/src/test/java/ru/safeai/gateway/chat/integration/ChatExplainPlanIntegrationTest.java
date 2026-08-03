package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
class ChatExplainPlanIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Test
    void succeededHistoryQueryUsesPartialHistoryIndex() {
        String plan = explainInTransaction(
                """
                select turn.id,
                       turn.user_message_id,
                       turn.assistant_message_id
                  from public.chat_turns turn
                 where turn.session_id = ?
                   and turn.state = 'SUCCEEDED'
                 order by turn.created_at desc, turn.id desc
                 limit 50
                """,
                CHAT_ID
        );

        assertThat(plan)
                .contains("idx_chat_turns_succeeded_history");
    }

    @Test
    void staleRecoveryQueryUsesProcessingLeaseIndex() {
        String plan = explainInTransaction(
                """
                select id, provider_call_started_at
                  from public.chat_turns
                 where state = 'PROCESSING'
                   and lease_until < ?
                 order by lease_until, id
                 limit 100
                """,
                Timestamp.from(NOW)
        );

        assertThat(plan)
                .containsAnyOf(
                        "idx_chat_turns_expired_processing",
                        "idx_chat_turns_processing_lease_started"
                );
    }

    @Test
    void visibleMessageQueryUsesTenantChatReadIndex() {
        String plan = explainInTransaction(
                """
                select id, created_at
                  from public.chat_messages
                 where session_id = ?
                   and role in ('USER', 'ASSISTANT')
                 order by created_at desc, id desc
                 limit 100
                """,
                CHAT_ID
        );

        assertThat(plan)
                .contains("idx_chat_messages_visible_session");
    }

    private String explainInTransaction(
            String sql,
            Object... arguments
    ) {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        return transaction.execute(status -> {
            jdbcTemplate.execute("set local enable_seqscan = off");
            List<String> lines = jdbcTemplate.query(
                    "explain " + sql,
                    (resultSet, rowNumber) -> resultSet.getString(1),
                    arguments
            );
            return String.join("\n", lines);
        });
    }
}
