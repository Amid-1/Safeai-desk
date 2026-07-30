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
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
@Import(
        RequiredAuditPolicyIntegrationTest
                .PolicyTestConfiguration.class
)
class RequiredAuditPolicyIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private RequiredAuditBusinessOperation
            businessOperation;

    @MockitoBean
    private AuditOutboxWriter outboxWriter;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void requiredAuditFailureRollsBackSecuritySensitiveBusinessOperation() {
        UUID organizationId = UUID.randomUUID();

        String originalName =
                "Required Before " + organizationId;

        insertOrganization(
                organizationId,
                originalName,
                true
        );

        doThrow(
                new TransactionSystemException(
                        "simulated required audit failure"
                )
        ).when(outboxWriter)
                .writeRequired(any());

        assertThatThrownBy(() ->
                businessOperation.renameOrganization(
                        organizationId,
                        "Required After " + organizationId
                )
        )
                .isInstanceOf(
                        TransactionSystemException.class
                )
                .hasMessageContaining(
                        "simulated required audit failure"
                );

        assertThat(
                queryString(
                        """
                        select name
                        from public.organizations
                        where id = ?
                        """,
                        organizationId
                )
        ).isEqualTo(originalName);

        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PolicyTestConfiguration {

        @Bean
        RequiredAuditBusinessOperation
        requiredAuditBusinessOperation(
                JdbcTemplate jdbcTemplate,
                AuditEventService auditEventService
        ) {
            return new RequiredAuditBusinessOperation(
                    jdbcTemplate,
                    auditEventService
            );
        }
    }

    public static class RequiredAuditBusinessOperation {

        private final JdbcTemplate jdbcTemplate;
        private final AuditEventService auditEventService;

        RequiredAuditBusinessOperation(
                JdbcTemplate jdbcTemplate,
                AuditEventService auditEventService
        ) {
            this.jdbcTemplate = jdbcTemplate;
            this.auditEventService =
                    auditEventService;
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

            auditEventService.recordSystem(
                    organizationId,
                    AuditEventType
                            .ORGANIZATION_NAME_CHANGED,
                    Map.of("newName", newName)
            );
        }
    }
}
