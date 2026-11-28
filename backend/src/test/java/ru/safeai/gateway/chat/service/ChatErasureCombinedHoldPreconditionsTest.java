// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/chat/service/ChatErasureCombinedHoldPreconditionsTest.java
package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ChatErasureCombinedHoldPreconditionsTest {

    // Independent, exact SQL contract: both hold sources must be tenant-scoped.
    private static final String EXPECTED_HOLD_SQL = """
            SELECT EXISTS(SELECT 1 FROM public.chat_retention_holds_v86 h
                           WHERE h.chat_id=c.id AND h.organization_id=c.organization_id
                             AND h.released_at IS NULL)
                OR EXISTS(SELECT 1 FROM public.erasure_review_holds_v105 h
                    JOIN public.erasure_requests_v103 r
                      ON r.id=h.request_id AND r.organization_id=h.organization_id
                    WHERE r.organization_id=c.organization_id AND r.resource_type='CHAT'
                      AND r.resource_id=c.id AND h.released_at IS NULL)
            FROM public.chat_sessions c WHERE c.id=?
            """;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatErasurePreconditions checks =
            new ChatErasurePreconditions(jdbc);

    private final UUID chat = UUID.randomUUID();

    @Test
    void combinedHoldQueryChecksBothSourcesTenantResourceAndUnknown() {
        when(jdbc.queryForObject(
                EXPECTED_HOLD_SQL,
                Boolean.class,
                chat
        ))
                .thenReturn(true)
                .thenReturn(false)
                .thenReturn(null);

        assertThat(checks.hasRetentionHold(chat)).isTrue();
        assertThat(checks.hasRetentionHold(chat)).isFalse();

        assertThatThrownBy(() -> checks.hasRetentionHold(chat))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAT_ERASURE_RETENTION_CHECK_UNKNOWN");

        verify(jdbc, times(3)).queryForObject(
                EXPECTED_HOLD_SQL,
                Boolean.class,
                chat
        );

        verifyNoMoreInteractions(jdbc);
    }
}
