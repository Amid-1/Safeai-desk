package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.chat.dto.ChatErasureRequest;
import ru.safeai.gateway.chat.service.ChatErasureWorker;
import ru.safeai.gateway.chat.service.ChatLifecycleService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.dao.DataAccessException;

/** An operator never receives app or worker DML privileges: it invokes only
 * the audited SECURITY DEFINER functions from a separate PostgreSQL login. */
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
class ChatErasureOperatorV86IntegrationTest extends AbstractChatPostgresIntegrationTest {
    @Autowired private ChatLifecycleService lifecycle;
    @Autowired private ChatErasureWorker worker;

    private JdbcTemplate operator() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
            "safeai_chat_operator_test", "test-only-operator-password");
        return new JdbcTemplate(ds);
    }

    private JdbcTemplate secondOperator() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
            "safeai_chat_operator_test2", "test-only-operator2-password");
        return new JdbcTemplate(ds);
    }

    private void approveTwice(String action, UUID subject, String ticket) {
        operator().queryForObject("SELECT public.chat_operator_approve_v86(?,?,?)",
            Object.class,action,subject,ticket);
        secondOperator().queryForObject("SELECT public.chat_operator_approve_v86(?,?,?)",
            Object.class,action,subject,ticket);
    }

    private record Scenario(UUID chatId, UUID owner, SafeAiUserPrincipal actor) { }

    private Scenario scenario() {
        UUID owner=UUID.randomUUID(), chatId=UUID.randomUUID();
        insertUser(owner, ORGANIZATION_ID,"operator-"+owner+"@example.test", true,"ADMIN", Instant.now());
        jdbcTemplate.update("""
            INSERT INTO public.chat_sessions
                (id,organization_id,user_id,title,title_source,created_at,updated_at,version)
            VALUES(?,?,?,'Hold case','USER',clock_timestamp(),clock_timestamp(),0)
        """,chatId,ORGANIZATION_ID,owner);
        insertUserMessage(chatId,ORGANIZATION_ID,UUID.randomUUID(),"PRIVATE_HOLD_MARKER",Instant.now());
        var actor=SafeAiUserPrincipal.accessTokenPrincipal(owner,ORGANIZATION_ID,
            0L,0L,Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        lifecycle.archive(chatId,actor);
        long version=java.util.Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT version FROM public.chat_sessions WHERE id=?",Long.class,chatId),
            "Archived chat must have a persisted version: " + chatId);
        lifecycle.acceptErasure(chatId,version,"erase:"+UUID.randomUUID(),
            new ChatErasureRequest("Hold case","УДАЛИТЬ НАВСЕГДА"),actor);
        return new Scenario(chatId,owner,actor);
    }

    @Test @WithMockUser(roles="ADMIN")
    void operatorReleaseHoldAndResumeIsAudited() {
        var h=scenario();
        UUID hold=UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO public.chat_retention_holds_v86(id,chat_id,organization_id,reason_code)
            VALUES(?,?,?,'LEGAL_HOLD')
        """,hold,h.chatId(),ORGANIZATION_ID);
        worker.poll();
        assertThat(lifecycle.erasureStatus(h.chatId(),h.actor()).state())
            .isEqualTo("BLOCKED_BY_RETENTION");
        UUID op=jdbcTemplate.queryForObject("""
            SELECT id FROM public.chat_erasure_operations_v86 WHERE chat_id=?
        """,UUID.class,h.chatId());
        operator().queryForObject("SELECT public.chat_operator_approve_v86(?,?,?)",
            Object.class,"RELEASE_HOLD",hold,"TICKET-RELEASE-123");
        assertThatThrownBy(() -> operator().queryForObject(
            "SELECT public.chat_operator_release_hold_v86(?,?)", Object.class,
            hold,"TICKET-RELEASE-123")).isInstanceOf(DataAccessException.class);
        secondOperator().queryForObject("SELECT public.chat_operator_approve_v86(?,?,?)",
            Object.class,"RELEASE_HOLD",hold,"TICKET-RELEASE-123");
        operator().queryForObject("SELECT public.chat_operator_release_hold_v86(?,?)",
            Object.class, hold, "TICKET-RELEASE-123");
        approveTwice("RESUME",op,"TICKET-RESUME-123");
        operator().queryForObject("SELECT public.chat_operator_resolve_erasure_v86(?,?,?)",
            Object.class, op, "RESUME", "TICKET-RESUME-123");
        worker.poll();
        assertThat(lifecycle.erasureStatus(h.chatId(),h.actor()).state()).isEqualTo("ERASED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM public.audit_outbox WHERE organization_id=?
              AND event_type IN ('CHAT_RETENTION_HOLD_RELEASED','CHAT_CONTENT_ERASURE_RESUMED')
        """,Integer.class,ORGANIZATION_ID)).isEqualTo(2);
    }

    @Test @WithMockUser(roles="ADMIN")
    void operatorCanCancelQuarantinedAttemptWithoutRedacting() {
        var h=scenario();
        UUID hold=UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO public.chat_retention_holds_v86(id,chat_id,organization_id,reason_code)
            VALUES(?,?,?,'LEGAL_HOLD')
        """,hold,h.chatId(),ORGANIZATION_ID);
        worker.poll();
        UUID op=jdbcTemplate.queryForObject(
            "SELECT id FROM public.chat_erasure_operations_v86 WHERE chat_id=?", UUID.class,h.chatId());
        approveTwice("CANCEL",op,"TICKET-CANCEL-123");
        operator().queryForObject("SELECT public.chat_operator_resolve_erasure_v86(?,?,?)",
            Object.class,op,"CANCEL","TICKET-CANCEL-123");
        assertThat(lifecycle.erasureStatus(h.chatId(),h.actor()).state()).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM public.chat_messages WHERE session_id=?
              AND content='PRIVATE_HOLD_MARKER'
        """,Integer.class,h.chatId())).isEqualTo(1);
        lifecycle.restore(h.chatId(),h.actor());
        assertThat(jdbcTemplate.queryForObject("""
            SELECT archived_at IS NULL FROM public.chat_sessions WHERE id=?
        """,Boolean.class,h.chatId())).isTrue();
    }
}
