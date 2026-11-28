package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.chat.dto.ChatErasureOperationResponse;
import ru.safeai.gateway.chat.dto.ChatErasureRequest;
import ru.safeai.gateway.chat.service.ChatErasureWorker;
import ru.safeai.gateway.chat.service.ChatLifecycleService;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Run against a REAL PostgreSQL/Testcontainers instance with Flyway V1..V86.
 * No immutable guards or FK are disabled; no truncation of accounting evidence.
 * Feature flags apply to the test profile only.
 */
@SpringBootTest(properties = {
        "safeai.chat.erasure.accept-enabled=true",
        "safeai.chat.erasure.executor-enabled=true",
        "safeai.chat.erasure.retention-approved=true",
        "safeai.chat.erasure.poll-delay=PT24H",
        "safeai.chat.erasure.worker-jdbc-url=${spring.datasource.url}",
        "safeai.chat.erasure.worker-username=safeai_chat_erasure_worker",
        "safeai.chat.erasure.worker-password=test-only-worker-password",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
class ChatContentErasureV86IntegrationTest extends AbstractChatPostgresIntegrationTest {
    @Autowired private ChatLifecycleService lifecycle;
    @Autowired private ChatErasureWorker worker;

    @Test
    @WithMockUser(roles = "ADMIN")
    void archiveRestoreAndIrreversibleContentErasureKeepAuditAndEvidence() {
        UUID actorId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        insertUser(actorId, ORGANIZATION_ID,
                "chat-erasure-" + actorId + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO public.chat_sessions
                    (id,organization_id,user_id,title,title_source,created_at,updated_at,version)
                VALUES (?,?,?,'Confidential chat','USER',clock_timestamp(),clock_timestamp(),0)
                """, chatId, ORGANIZATION_ID, actorId);
        UUID messageId = insertUserMessage(chatId, ORGANIZATION_ID,
                UUID.randomUUID(), "PRIVATE_ERASURE_TEST_STRING", Instant.now());
        var actor = SafeAiUserPrincipal.accessTokenPrincipal(actorId, ORGANIZATION_ID,
                0L, 0L, Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        lifecycle.archive(chatId, actor);
        assertThat(lifecycle.archived(actor, org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent()).extracting(ru.safeai.gateway.chat.dto.ChatResponse::id).contains(chatId);
        lifecycle.restore(chatId, actor);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT archived_at IS NULL FROM public.chat_sessions WHERE id=?
                """, Boolean.class, chatId)).isTrue();
        lifecycle.archive(chatId, actor);
        long version = java.util.Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT version FROM public.chat_sessions WHERE id=?
                """, Long.class, chatId),
                "Archived chat must have a persisted version: " + chatId);
        var cap = lifecycle.capability(chatId, actor);
        assertThat(cap.allowed()).isTrue();
        assertThat(cap.version()).isEqualTo(version);
        String key = "erase:" + UUID.randomUUID();
        ChatErasureRequest confirmation = new ChatErasureRequest(
                "Confidential chat", "УДАЛИТЬ НАВСЕГДА");
        ChatErasureOperationResponse accepted = lifecycle.acceptErasure(
                chatId, version, key, confirmation, actor);
        assertThat(accepted.state()).isEqualTo("ERASING");
        assertThat(lifecycle.acceptErasure(chatId, version, key, confirmation, actor))
                .isEqualTo(accepted);
        assertThatThrownBy(() -> lifecycle.acceptErasure(chatId, version, key,
                new ChatErasureRequest("Changed title", "УДАЛИТЬ НАВСЕГДА"), actor))
                .isInstanceOf(ru.safeai.gateway.common.exception.ConflictException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> lifecycle.restore(chatId, actor))
                .isInstanceOf(ResourceNotFoundException.class);

        // A direct attempt to modify completed message evidence is still denied.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.chat_messages SET content='FORGED' WHERE id=?
                """, messageId)).isInstanceOf(DataIntegrityViolationException.class);
        // Neither the Java API nor raw SQL may forge terminal success.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.chat_erasure_operations_v86
                   SET state='ERASED', completed_at=clock_timestamp()
                 WHERE chat_id=?
                """, chatId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.chat_messages SET content='[СОДЕРЖИМОЕ УДАЛЕНО]'
                WHERE id=?
                """, messageId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT content FROM public.chat_messages WHERE id=?
                """, String.class, messageId)).isEqualTo("PRIVATE_ERASURE_TEST_STRING");
        worker.poll();
        assertThat(lifecycle.erasureStatus(chatId, actor).state()).isEqualTo("ERASED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT content FROM public.chat_messages WHERE id=?
                """, String.class, messageId)).isEqualTo("[СОДЕРЖИМОЕ УДАЛЕНО]");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT title FROM public.chat_sessions WHERE id=?
                """, String.class, chatId)).isEqualTo("[СОДЕРЖИМОЕ УДАЛЕНО]");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.audit_outbox
                WHERE organization_id=? AND event_type='CHAT_CONTENT_ERASURE_COMPLETED'
                  AND (details->>'chatId')=?
                """, Integer.class, ORGANIZATION_ID, chatId.toString())).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.chat_messages SET content='RESTORED' WHERE id=?
                """, messageId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM public.chat_erasure_operations_v86 WHERE chat_id=?
                """, chatId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> lifecycle.archivedDetails(chatId, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void legacyArchiveCannotRaceActiveChatTurnAtDatabaseBoundary() {
        UUID actorId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        insertUser(actorId, ORGANIZATION_ID,
                "chat-active-" + actorId + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO public.chat_sessions
                    (id,organization_id,user_id,title,title_source,created_at,updated_at,version)
                VALUES (?,?,?,'Active chat','USER',clock_timestamp(),clock_timestamp(),0)
                """, chatId, ORGANIZATION_ID, actorId);
        UUID requestId = UUID.randomUUID();
        UUID messageId = insertUserMessage(chatId, ORGANIZATION_ID,
                requestId, "ACTIVE_MESSAGE_CONTENT", NOW);
        insertProcessingTurn(chatId, ORGANIZATION_ID, actorId,
                requestId, messageId, UUID.randomUUID(), NOW.plusSeconds(3600), NOW);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE public.chat_sessions
                   SET archived_at=clock_timestamp(), archived_by_user_id=?, version=version+1
                 WHERE id=?
                """, actorId, chatId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT archived_at IS NULL FROM public.chat_sessions WHERE id=?
                """, Boolean.class, chatId)).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void activeRetentionHoldBlocksContentErasureWithoutTriggerBypass() {
        UUID actorId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        insertUser(actorId, ORGANIZATION_ID,
                "chat-hold-" + actorId + "@example.test", true, "ADMIN", Instant.now());
        jdbcTemplate.update("""
                INSERT INTO public.chat_sessions
                    (id,organization_id,user_id,title,title_source,created_at,updated_at,version)
                VALUES (?,?,?,'Held chat','USER',clock_timestamp(),clock_timestamp(),0)
                """, chatId, ORGANIZATION_ID, actorId);
        var actor = SafeAiUserPrincipal.accessTokenPrincipal(actorId, ORGANIZATION_ID,
                0L, 0L, Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        lifecycle.archive(chatId, actor);
        jdbcTemplate.update("""
                INSERT INTO public.chat_retention_holds_v86(id,chat_id,organization_id,reason_code)
                VALUES(?,?,?,'LEGAL_HOLD')
                """, UUID.randomUUID(), chatId, ORGANIZATION_ID);
        assertThat(lifecycle.capability(chatId, actor).allowed()).isFalse();
        assertThatThrownBy(() -> lifecycle.acceptErasure(chatId,
                lifecycle.capability(chatId, actor).version(), "erase:" + UUID.randomUUID(),
                new ChatErasureRequest("Held chat", "УДАЛИТЬ НАВСЕГДА"), actor))
                .hasMessageContaining("хранения");
    }
}
