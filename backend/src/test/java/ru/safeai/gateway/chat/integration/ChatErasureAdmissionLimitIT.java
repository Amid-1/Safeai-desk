package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.safeai.gateway.chat.dto.ChatErasureRequest;
import ru.safeai.gateway.chat.service.ChatLifecycleService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** True PostgreSQL boundary: 10000 rows allowed, 10001 rejected before durable intent. */
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "safeai.chat.erasure.accept-enabled=true",
    "safeai.chat.erasure.executor-enabled=true",
    "safeai.chat.erasure.retention-approved=true",
    "safeai.chat.erasure.worker-username=safeai_chat_erasure_worker",
    "safeai.chat.erasure.worker-password=test-only-worker-password"
})
class ChatErasureAdmissionLimitIT extends AbstractChatPostgresIntegrationTest {
    @DynamicPropertySource
    static void workerUrl(DynamicPropertyRegistry registry) {
        registry.add("safeai.chat.erasure.worker-jdbc-url",POSTGRES::getJdbcUrl);
    }

    @Autowired private ChatLifecycleService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private SafeAiUserPrincipal setUpArchivedAdminChat(int rows) {
        // Exactly-one-role constraint remains intact, merely changes the role.
        jdbcTemplate.update("""
            UPDATE public.user_roles SET role_id=? WHERE user_id=?
            """, ADMIN_ROLE_ID, USER_ID);
        jdbcTemplate.update("""
            INSERT INTO public.chat_messages
              (id,session_id,organization_id,role,content,client_request_id,
               created_at,status,usage_status,pricing_status)
            SELECT gen_random_uuid(),?,?,'USER','test message',gen_random_uuid(),
                   clock_timestamp(),'COMPLETED','NOT_APPLICABLE','NOT_APPLICABLE'
            FROM generate_series(1,?)
            """, CHAT_ID, ORGANIZATION_ID, rows);
        jdbcTemplate.update("""
            UPDATE public.chat_sessions
               SET archived_at=clock_timestamp(),archived_by_user_id=?,version=version+1
             WHERE id=?
            """, USER_ID, CHAT_ID);
       SafeAiUserPrincipal admin = SafeAiUserPrincipal.accessTokenPrincipal(
               USER_ID, ORGANIZATION_ID, 0L, 0L,
               List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
       SecurityContextHolder.getContext().setAuthentication(
               new UsernamePasswordAuthenticationToken(
                       admin, null, admin.getAuthorities()));
       return admin;
    }

    @Test
    void exactlyTenThousandMessagesAreWithinAdmissionLimit() {
        SafeAiUserPrincipal admin=setUpArchivedAdminChat(10_000);
        var capability=service.capability(CHAT_ID,admin);
        assertThat(capability.allowed()).isTrue();
    }

    @Test
    void tenThousandAndOneMessagesAreRejectedBeforeErasureIntent() {
        SafeAiUserPrincipal admin=setUpArchivedAdminChat(10_001);
        var capability=service.capability(CHAT_ID,admin);
        assertThat(capability.allowed()).isFalse();
        assertThat(capability.reason()).isEqualTo("CHAT_TOO_LARGE_FOR_ATOMIC_ERASURE");
       long version=Objects.requireNonNull(jdbcTemplate.queryForObject("""
           SELECT version FROM public.chat_sessions WHERE id=?
           """,Long.class,CHAT_ID), "Archive fixture must exist");
        assertThatThrownBy(()->service.acceptErasure(CHAT_ID,version,
                "oversize-test-"+UUID.randomUUID(),
                new ChatErasureRequest("Integration chat","УДАЛИТЬ НАВСЕГДА"),admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("лимит");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM public.chat_erasure_operations_v86 WHERE chat_id=?
            """,Long.class,CHAT_ID)).isZero();
       // Failure happens before durable intent, with every source row intact.
       assertThat(jdbcTemplate.queryForObject("""
           SELECT count(*) FROM public.chat_messages
            WHERE session_id=? AND content='test message'
           """,Long.class,CHAT_ID)).isEqualTo(10_001L);
    }
}
