// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/chat/service/ChatErasurePreconditionsTest.java
package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatErasurePreconditionsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatErasurePreconditions checks = new ChatErasurePreconditions(jdbc);
    private final UUID chat = UUID.randomUUID();

    @Test
    void holdCheckDistinguishesTrueFalseAndUnknown() {
        when(jdbc.queryForObject(contains("chat_retention_holds_v86"), eq(Boolean.class), eq(chat)))
                .thenReturn(true, false, null);
        assertThat(checks.hasRetentionHold(chat)).isTrue();
        assertThat(checks.hasRetentionHold(chat)).isFalse();
        assertThatThrownBy(() -> checks.hasRetentionHold(chat))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAT_ERASURE_RETENTION_CHECK_UNKNOWN");
    }

    @Test
    void executionCheckDistinguishesTrueFalseAndUnknown() {
        when(jdbc.queryForObject(eq("SELECT public.chat_has_active_execution_v86(?)"), eq(Boolean.class), eq(chat)))
                .thenReturn(true, false, null);
        assertThat(checks.hasInFlightTurn(chat)).isTrue();
        assertThat(checks.hasInFlightTurn(chat)).isFalse();
        assertThatThrownBy(() -> checks.hasInFlightTurn(chat))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAT_ERASURE_EXECUTION_CHECK_UNKNOWN");
    }

    @Test
    void atomicLimitCheckDistinguishesTrueFalseAndUnknown() {
        when(jdbc.queryForObject(contains("knowledge_retrieval_runs"), eq(Boolean.class),
                eq(chat), eq(ChatErasureLimits.MAX_ROWS_PER_KIND),
                eq(chat), eq(ChatErasureLimits.MAX_ROWS_PER_KIND)))
                .thenReturn(true, false, null);
        assertThat(checks.exceedsAtomicErasureLimit(chat)).isTrue();
        assertThat(checks.exceedsAtomicErasureLimit(chat)).isFalse();
        assertThatThrownBy(() -> checks.exceedsAtomicErasureLimit(chat))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAT_ERASURE_LIMIT_CHECK_UNKNOWN");
    }
}
