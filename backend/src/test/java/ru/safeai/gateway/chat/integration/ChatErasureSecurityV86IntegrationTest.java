package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL, V0(test-only)-V88. No disabling immutability triggers. */
@SpringBootTest
@ActiveProfiles("test")
class ChatErasureSecurityV86IntegrationTest extends AbstractChatPostgresIntegrationTest {

    @Test void dedicatedRoleAndRagGuardsExist() {
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM pg_trigger WHERE tgname IN
            ('trg_chat_rag_permit_entry_v86','trg_chat_rag_attempt_entry_v86',
             'trg_completed_chat_message_update_v67','trg_knowledge_retrieval_runs_immutable_v44')
             AND tgenabled IN ('O','A')
        """, Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT has_table_privilege('safeai_app',
                'public.chat_erasure_operations_v86','UPDATE')
        """, Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT has_table_privilege('safeai_chat_erasure_worker',
                'public.chat_erasure_operations_v86','UPDATE')
        """, Boolean.class)).isTrue();
    }

    @Test void realAppAndWorkerLoginsCannotObtainOperatorPrivileges() {
        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), "safeai_app", "test-only-app-password"));
        JdbcTemplate worker = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), "safeai_chat_erasure_worker", "test-only-worker-password"));
        assertThat(app.queryForObject("SELECT session_user", String.class)).isEqualTo("safeai_app");
        assertThat(worker.queryForObject("SELECT session_user", String.class))
            .isEqualTo("safeai_chat_erasure_worker");
        assertThatThrownBy(() -> app.update("""
            UPDATE public.chat_erasure_operations_v86 SET attempts=attempts+1
        """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> worker.update("""
            UPDATE public.chat_retention_holds_v86 SET released_at=clock_timestamp()
        """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> app.update("""
            INSERT INTO public.chat_erasure_operator_approvals_v86
                (action,subject_id,ticket,operator_db_login)
            VALUES ('CANCEL',gen_random_uuid(),'CASE-2026-1000',current_user)
        """)).isInstanceOf(DataAccessException.class);
    }

    @Test void ordinaryDbSessionCannotForgeContentErasureUsingGucs() {
        UUID messageId = insertUserMessage(CHAT_ID, ORGANIZATION_ID,
            UUID.randomUUID(), "CONFIDENTIAL_TEST_VALUE", java.time.Instant.now());
        // test 'safeai' can have DML rights; a forged GUC still cannot pass the
        // worker session_user gate in the V67 and V86 triggers.
        jdbcTemplate.queryForObject("SELECT set_config('safeai.chat.erasure_operation',?,false)",
            String.class, UUID.randomUUID().toString());
        jdbcTemplate.queryForObject("SELECT set_config('safeai.chat.erasure_token',?,false)",
            String.class, UUID.randomUUID().toString());
        assertThatThrownBy(() -> jdbcTemplate.update("""
            UPDATE public.chat_messages SET content='[СОДЕРЖИМОЕ УДАЛЕНО]' WHERE id=?
        """,messageId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
