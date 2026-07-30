package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
@Import(
        BestEffortAuditPolicyIntegrationTest
                .PolicyTestConfiguration.class
)
class BestEffortAuditPolicyIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private BestEffortBusinessOperation
            businessOperation;

    @MockitoBean
    private AuditOutboxWriter outboxWriter;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void auditCommitFailureDoesNotRollbackBestEffortBusinessOperation() {
        UUID organizationId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Before Best Effort " + organizationId,
                true
        );

        doThrow(
                new TransactionSystemException(
                        "simulated audit commit failure"
                )
        ).when(outboxWriter)
                .writeStandalone(any());

        assertThatCode(() ->
                businessOperation.renameOrganization(
                        organizationId,
                        "After Best Effort "
                                + organizationId
                )
        ).doesNotThrowAnyException();

        assertThat(
                queryString(
                        """
                        select name
                        from public.organizations
                        where id = ?
                        """,
                        organizationId
                )
        ).isEqualTo(
                "After Best Effort "
                        + organizationId
        );

        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PolicyTestConfiguration {

        @Bean
        BestEffortBusinessOperation
        bestEffortBusinessOperation(
                JdbcTemplate jdbcTemplate,
                BestEffortStandaloneAuditService
                        auditService
        ) {
            return new BestEffortBusinessOperation(
                    jdbcTemplate,
                    auditService
            );
        }
    }

    public static class BestEffortBusinessOperation {

        private final JdbcTemplate jdbcTemplate;
        private final BestEffortStandaloneAuditService
                auditService;

        BestEffortBusinessOperation(
                JdbcTemplate jdbcTemplate,
                BestEffortStandaloneAuditService
                        auditService
        ) {
            this.jdbcTemplate = jdbcTemplate;
            this.auditService = auditService;
        }

        @Transactional
        public void renameOrganization(
                UUID organizationId,
                String newName
        ) {
            jdbcTemplate.update(
                    """
                    update public.organizations
                    set name = ?
                    where id = ?
                    """,
                    newName,
                    organizationId
            );

            AuditActor actor = new AuditActor(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "standalone@test.com",
                    "Standalone Actor"
            );

            auditService.tryRecord(
                    actor,
                    organizationId,
                    AuditEventType.RATE_LIMIT_EXCEEDED,
                    Map.of(
                            "type",
                            "AI_MESSAGE_USER",
                            "limit",
                            100
                    )
            );
        }
    }
}
