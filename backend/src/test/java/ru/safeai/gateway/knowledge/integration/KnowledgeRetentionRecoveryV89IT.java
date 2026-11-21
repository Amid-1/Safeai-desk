package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real database two-person control; never fakes Object Lock clearance.
 * The resumed operation still runs through the verified S3 purge executor. */
@ActiveProfiles("test")
@SpringBootTest
class KnowledgeRetentionRecoveryV89IT extends AbstractPostgresIntegrationTest {
    @Test
    void oneOperatorCannotResumeBlockedBaseAndSecondIndependentReviewerCan() throws Exception {
        UUID org=UUID.randomUUID();
        UUID creator=UUID.randomUUID();
        UUID base=UUID.randomUUID();
        UUID operation=UUID.randomUUID();
        insertOrganization(org,"Retention Review "+base,true);
        insertUser(creator,org,"review-"+creator+"@test.example",true,"ADMIN",Instant.now().minusSeconds(3600));
        jdbcTemplate.update("""
            INSERT INTO public.knowledge_bases
                (id,organization_id,name,visibility,enabled,created_by_user_id)
            VALUES(?,?,?,'ORGANIZATION',true,?)
            """,base,org,"Retention base "+base,creator);
        jdbcTemplate.update("""
            INSERT INTO public.knowledge_base_deletions_v82
                (id,organization_id,knowledge_base_id,requested_by_user_id,
                 expected_version,idempotency_key)
            VALUES(?,?,?,?,0,?)
            """,operation,org,base,creator,"retention-"+operation);
        jdbcTemplate.update("""
            UPDATE public.knowledge_bases
               SET lifecycle_state='DELETING',enabled=false WHERE id=?
            """,base);
        jdbcTemplate.update("""
            UPDATE public.knowledge_base_deletions_v82
               SET state='BLOCKED_BY_RETENTION',last_error_code='CHILD_BLOCKED_BY_RETENTION',
                   updated_at=clock_timestamp()
             WHERE id=?
            """,operation);
        Timestamp blockedAt=jdbcTemplate.queryForObject("""
            SELECT updated_at FROM public.knowledge_base_deletions_v82 WHERE id=?
            """,Timestamp.class,operation);
        assertThat(blockedAt).isNotNull();
        assertThatThrownBy(()->jdbcTemplate.update("""
            UPDATE public.knowledge_base_deletions_v82
               SET state='DELETING' WHERE id=?
            """,operation)).hasMessageContaining("blocked Knowledge base");

        String sha="a".repeat(64);
        String ticket="OPS-RETENTION-0001";
        String first=review("safeai_knowledge_operator_test_a", "test-only-knowledge-operator-a",
                operation,blockedAt,ticket,sha);
        assertThat(first).isEqualTo("REVIEW_RECORDED");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT state FROM public.knowledge_base_deletions_v82 WHERE id=?
            """,String.class,operation)).isEqualTo("BLOCKED_BY_RETENTION");

        String second=review("safeai_knowledge_operator_test_b", "test-only-knowledge-operator-b",
                operation,blockedAt,ticket,sha);
        assertThat(second).isEqualTo("RESUMED_FOR_REVERIFICATION");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT state FROM public.knowledge_base_deletions_v82 WHERE id=?
            """,String.class,operation)).isEqualTo("DELETING");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM public.knowledge_retention_recovery_reviews_v89
            WHERE kind='BASE' AND operation_id=?
            """,Long.class,operation)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM public.audit_outbox WHERE event_type='KNOWLEDGE_RETENTION_RECOVERY_RESUMED'
              AND details->>'operationId'=?
            """,Long.class,operation.toString())).isEqualTo(1);
    }

    private static String review(String dbUser,String dbPassword,UUID op,Timestamp blockedAt,
                                 String ticket,String evidenceSha256) throws Exception {
        try (Connection con=DriverManager.getConnection(POSTGRES.getJdbcUrl(),dbUser,dbPassword);
             PreparedStatement stmt=con.prepareStatement("""
                 SELECT public.review_blocked_knowledge_erasure_v89(
                     'BASE'::varchar,?::uuid,?::timestamptz,?::varchar,?::char(64))
                 """)) {
            stmt.setObject(1,op);
            stmt.setTimestamp(2,blockedAt);
            stmt.setString(3,ticket);
            stmt.setString(4,evidenceSha256);
            try (ResultSet result=stmt.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("No recovery response");
                return result.getString(1);
            }
        }
    }
}
