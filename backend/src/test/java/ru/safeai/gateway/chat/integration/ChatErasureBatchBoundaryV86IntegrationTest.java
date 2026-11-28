package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.test.context.support.WithMockUser;
import ru.safeai.gateway.chat.service.ChatErasureWorker;
import ru.safeai.gateway.chat.service.ChatLifecycleService;
import ru.safeai.gateway.chat.dto.ChatErasureRequest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "safeai.chat.erasure.accept-enabled=true",
    "safeai.chat.erasure.executor-enabled=true",
    "safeai.chat.erasure.retention-approved=true",
    "safeai.chat.erasure.poll-delay=PT24H",
    "safeai.chat.erasure.worker-jdbc-url=${spring.datasource.url}",
    "safeai.chat.erasure.worker-username=safeai_chat_erasure_worker",
    "safeai.chat.erasure.worker-password=test-only-worker-password"
})
@ActiveProfiles("test")
class ChatErasureBatchBoundaryV86IntegrationTest extends AbstractChatPostgresIntegrationTest {
    @Autowired private ChatLifecycleService lifecycle;
    @Autowired private ChatErasureWorker worker;

    @ParameterizedTest
    @ValueSource(ints = {0,1,99,100,101})
    @WithMockUser(roles = "ADMIN")
    void smallBoundariesFullyErase(int count) { runCase(count,true); }

    @Test @Tag("slow")
    @EnabledIfSystemProperty(named="safeai.erasure.load-tests",matches="true")
    @WithMockUser(roles = "ADMIN")
    void exactlyTenThousandCompletesWithoutOffByOne() { runCase(10_000,true); }

    @Test @Tag("slow")
    @EnabledIfSystemProperty(named="safeai.erasure.load-tests",matches="true")
    @WithMockUser(roles = "ADMIN")
    void tenThousandAndOneIsRejectedBeforeIntentAndWithoutAnyRedaction() {
        runCase(10_001,false);
    }

    void runCase(int count, boolean shouldErase) {
        UUID owner=UUID.randomUUID(), chatId=UUID.randomUUID();
        insertUser(owner,ORGANIZATION_ID,"erasure-batch-"+owner+"@example.test",
            true,"ADMIN",Instant.now());
        jdbcTemplate.update("""
            INSERT INTO public.chat_sessions
                (id,organization_id,user_id,title,title_source,created_at,updated_at,version)
            VALUES(?,?,?,'Batch testing','USER',clock_timestamp(),clock_timestamp(),0)
        """,chatId,ORGANIZATION_ID,owner);
        for(int i=0;i<count;i++) {
            insertUserMessage(chatId,ORGANIZATION_ID,UUID.randomUUID(),
                "PRIVATE_BATCH_MARKER_"+i,Instant.now());
        }
        var actor=SafeAiUserPrincipal.accessTokenPrincipal(owner,ORGANIZATION_ID,
            0L,0L,Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        lifecycle.archive(chatId,actor);
        long version=java.util.Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT version FROM public.chat_sessions WHERE id=?",Long.class,chatId),
            "Archived chat must have a persisted version: " + chatId);
       if (shouldErase) {
           lifecycle.acceptErasure(chatId,version,"erase:"+UUID.randomUUID(),
               new ChatErasureRequest("Batch testing","УДАЛИТЬ НАВСЕГДА"),actor);
           worker.poll();
           assertThat(lifecycle.erasureStatus(chatId,actor).state()).isEqualTo("ERASED");
       } else {
           // Admission is fail-closed: no durable intent, worker or partial redaction.
           assertThat(lifecycle.capability(chatId,actor).reason())
               .isEqualTo("CHAT_TOO_LARGE_FOR_ATOMIC_ERASURE");
           org.assertj.core.api.Assertions.assertThatThrownBy(() ->
               lifecycle.acceptErasure(chatId,version,"erase:"+UUID.randomUUID(),
                   new ChatErasureRequest("Batch testing","УДАЛИТЬ НАВСЕГДА"),actor))
               .isInstanceOf(ru.safeai.gateway.common.exception.ConflictException.class);
           assertThat(jdbcTemplate.queryForObject(
               "SELECT count(*) FROM public.chat_erasure_operations_v86 WHERE chat_id=?",
               Long.class,chatId)).isZero();
           assertThat(jdbcTemplate.queryForObject(
               "SELECT erasure_state FROM public.chat_sessions WHERE id=?",
               String.class,chatId)).isEqualTo("AVAILABLE");
       }
        long untouched=java.util.Objects.requireNonNull(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM public.chat_messages
              WHERE session_id=? AND content LIKE 'PRIVATE_BATCH_MARKER_%'
        """,Long.class,chatId),
            "Message count query must return a non-null value for chat: " + chatId);
        assertThat(untouched).isEqualTo(shouldErase?0:count);
    }
}
