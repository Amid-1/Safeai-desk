package ru.safeai.gateway.model.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.integration.AbstractChatPostgresIntegrationTest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.exception.ModelRouteDeniedException;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Proves the PostgreSQL advisory request mutex around the route replay lookup.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.usage.rollup.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelRoutingRequestMutexPostgresIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Test
    void sameIdempotencyIdentityCannotCreateTwoFirstRouteDecisions()
            throws Exception {

        ModelCatalogRepository catalogRepository =
                new ModelCatalogRepository(jdbcTemplate);
        OrganizationModelPolicyRepository policyRepository =
                new OrganizationModelPolicyRepository(jdbcTemplate);
        ModelRouteDecisionRepository decisionRepository =
                new ModelRouteDecisionRepository(jdbcTemplate);
        RuntimeModelStatusService runtimeStatusService =
                mock(RuntimeModelStatusService.class);

        ModelRoutingService routing =
                ModelIntegrationTestSupport.routingService(
                        jdbcTemplate,
                        catalogRepository,
                        policyRepository,
                        decisionRepository,
                        runtimeStatusService,
                        mock(AuditEventService.class),
                        ModelTestFixtures.CLOCK
                );

        UUID plannedTurnId =
                UUID.randomUUID();
        UUID clientRequestId =
                UUID.randomUUID();

        ModelRouteRequest request =
                new ModelRouteRequest(
                        ORGANIZATION_ID,
                        USER_ID,
                        CHAT_ID,
                        plannedTurnId,
                        clientRequestId,
                        ModelTestFixtures.REQUEST_HASH,
                        "openai:missing:" + UUID.randomUUID(),
                        "hello",
                        List.of(),
                        Set.of(),
                        0L
                );

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_USER")
                        )
                );

        CountDownLatch ready =
                new CountDownLatch(2);
        CountDownLatch start =
                new CountDownLatch(1);

        try (var executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {

            var task =
                    (java.util.concurrent.Callable<UUID>) () -> {
                        ready.countDown();

                        if (!start.await(
                                10,
                                TimeUnit.SECONDS
                        )) {
                            throw new AssertionError(
                                    "Route mutex race start timed out"
                            );
                        }

                        return new TransactionTemplate(
                                transactionManager
                        ).execute(status -> {
                            try {
                                routing.decide(
                                        request,
                                        principal
                                );

                                throw new AssertionError(
                                        "Unconfigured organization must fail closed"
                                );

                            } catch (ModelRouteDeniedException exception) {
                                return exception.getDecisionId();
                            }
                        });
                    };

            var first =
                    executor.submit(task);
            var second =
                    executor.submit(task);

            assertThat(
                    ready.await(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            start.countDown();

            UUID firstDecisionId =
                    first.get(
                            20,
                            TimeUnit.SECONDS
                    );
            UUID secondDecisionId =
                    second.get(
                            20,
                            TimeUnit.SECONDS
                    );

            assertThat(firstDecisionId)
                    .isNotNull()
                    .isEqualTo(secondDecisionId);

            Integer persisted =
                    jdbcTemplate.queryForObject(
                            """
                            select count(*)
                            from public.model_route_decisions
                            where chat_id = ?
                              and client_request_id = ?
                            """,
                            Integer.class,
                            CHAT_ID,
                            clientRequestId
                    );

            assertThat(persisted)
                    .isEqualTo(1);

            ModelRouteDecision decision =
                    decisionRepository
                            .findById(firstDecisionId)
                            .orElseThrow();

            assertThat(decision.outcome())
                    .isEqualTo(ModelRouteOutcome.DENIED);
            assertThat(decision.reason())
                    .isEqualTo(ModelRouteReason.MODEL_NOT_ALLOWED);
            assertThat(decision.policyId())
                    .isNull();
        }

        /*
         * An unconfigured organization is rejected before runtime resolution;
         * the mutex/replay path must not accidentally probe the provider.
         */
        verifyNoInteractions(runtimeStatusService);
    }
}
