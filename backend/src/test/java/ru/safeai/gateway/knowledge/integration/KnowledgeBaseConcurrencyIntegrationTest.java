package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeBaseConcurrencyIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void concurrentEquivalentNames_produceOneSuccessAndOneConflict()
            throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Knowledge Base Concurrency "
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8),
                true
        );

        insertUser(
                adminId,
                organizationId,
                "kb-create-race-"
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                        + "@test.local",
                true,
                "ADMIN",
                Instant.now().minusSeconds(60)
        );

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        adminId,
                        organizationId,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );

        CountDownLatch start = new CountDownLatch(1);

        try (
                ExecutorService executor =
                        Executors.newFixedThreadPool(2)
        ) {
            Future<CreateOutcome> first =
                    executor.submit(() -> {
                        await(start);
                        return createOutcome(
                                "Concurrent Runbooks",
                                principal
                        );
                    });

            Future<CreateOutcome> second =
                    executor.submit(() -> {
                        await(start);
                        return createOutcome(
                                "  concurrent   runbooks  ",
                                principal
                        );
                    });

            try {
                start.countDown();

                List<CreateOutcome> outcomes =
                        List.of(
                                first.get(
                                        30,
                                        TimeUnit.SECONDS
                                ),
                                second.get(
                                        30,
                                        TimeUnit.SECONDS
                                )
                        );

                assertThat(outcomes)
                        .containsExactlyInAnyOrder(
                                CreateOutcome.SUCCESS,
                                CreateOutcome.CONFLICT
                        );
            } finally {
                start.countDown();
            }
        }

        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.knowledge_bases
                where organization_id = ?
                  and lower(name) = lower(?)
                """,
                Integer.class,
                organizationId,
                "Concurrent Runbooks"
        );

        assertThat(count).isEqualTo(1);
    }

    private CreateOutcome createOutcome(
            String name,
            SafeAiUserPrincipal principal
    ) {
        try {
            knowledgeBaseService.create(
                    new CreateKnowledgeBaseRequest(
                            name,
                            null,
                            KnowledgeBaseVisibility.MEMBERS
                    ),
                    principal
            );

            return CreateOutcome.SUCCESS;
        } catch (ConflictException exception) {
            return CreateOutcome.CONFLICT;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrency barrier timeout"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Concurrency test interrupted",
                    exception
            );
        }
    }

    private enum CreateOutcome {
        SUCCESS,
        CONFLICT
    }
}
