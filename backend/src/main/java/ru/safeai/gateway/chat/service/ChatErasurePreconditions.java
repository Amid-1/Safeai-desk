// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/chat/service/ChatErasurePreconditions.java
package ru.safeai.gateway.chat.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.UUID;

/** Shared V86 checks for capability and admission. Never issues an execution permit. */
final class ChatErasurePreconditions {
    private final JdbcTemplate jdbc;

    ChatErasurePreconditions(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    boolean hasInFlightTurn(UUID chatId) {
        return requiredBoolean(jdbc.queryForObject(
                "SELECT public.chat_has_active_execution_v86(?)", Boolean.class, chatId),
                "CHAT_ERASURE_EXECUTION_CHECK_UNKNOWN");
    }

    boolean exceedsAtomicErasureLimit(UUID chatId) {
        // Same predicates and per-kind limit as the V86 worker.
        return requiredBoolean(jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM public.chat_messages
                        WHERE session_id=? AND content IS DISTINCT FROM '[СОДЕРЖИМОЕ УДАЛЕНО]') > ?
                    OR (SELECT count(*) FROM public.knowledge_retrieval_runs r
                        JOIN public.chat_turns t ON t.id=r.chat_turn_id
                        WHERE t.session_id=? AND r.query_text IS DISTINCT FROM '[REDACTED:SHA256_ONLY]') > ?
                """, Boolean.class, chatId, ChatErasureLimits.MAX_ROWS_PER_KIND,
                chatId, ChatErasureLimits.MAX_ROWS_PER_KIND), "CHAT_ERASURE_LIMIT_CHECK_UNKNOWN");
    }

    boolean hasRetentionHold(UUID chatId) {
        // A read projection for UX/admission. V107 enforces the same union under
        // PostgreSQL locks; no successful capability check authorizes the worker.
        return requiredBoolean(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.chat_retention_holds_v86 h
                               WHERE h.chat_id=c.id AND h.organization_id=c.organization_id
                                 AND h.released_at IS NULL)
                    OR EXISTS(SELECT 1 FROM public.erasure_review_holds_v105 h
                        JOIN public.erasure_requests_v103 r
                          ON r.id=h.request_id AND r.organization_id=h.organization_id
                        WHERE r.organization_id=c.organization_id AND r.resource_type='CHAT'
                          AND r.resource_id=c.id AND h.released_at IS NULL)
                FROM public.chat_sessions c WHERE c.id=?
                """, Boolean.class, chatId), "CHAT_ERASURE_RETENTION_CHECK_UNKNOWN");
    }

    private static boolean requiredBoolean(Boolean value, String code) {
        // Match the worker's fail-closed semantics. Unknown is not permission.
        if (value == null) throw new IllegalStateException(code);
        return value;
    }
}
