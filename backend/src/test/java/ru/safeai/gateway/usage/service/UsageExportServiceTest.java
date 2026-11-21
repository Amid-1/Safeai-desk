// backend/src/test/java/ru/safeai/gateway/usage/service/UsageExportServiceTest.java
package ru.safeai.gateway.usage.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageExportRequest;
import ru.safeai.gateway.usage.dto.UsageProviderRow;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageExportServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void jobIsBoundToOwnerAndAuthVersionAndExportsExactEvidence() throws InterruptedException {
        var queries = mock(UsageQueryService.class);
        var physical = mock(UsagePhysicalSpendService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(physical.providers(eq(NOW.minusSeconds(60)), eq(NOW), eq(ORG), isNull(), isNull(), any()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    return List.of(new UsageProviderRow("mock", "1", "0", "0", "0",
                            "9007199254740993", "0", "0.000000000000000001"));
                });
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        var service = new UsageExportService(clock, queries, physical);
        try {
            var actor = actor(USER, ORG, 1, "ROLE_ADMIN");
            var job = service.start(request(null), actor);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.status(job.id(), actor).state()).isEqualTo(UsageExportService.State.RUNNING);
            assertThatThrownBy(() -> service.start(request(null), actor))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.download(job.id(), actor))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.status(job.id(), actor(USER, ORG, 2, "ROLE_ADMIN")))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.status(job.id(), actor(UUID.randomUUID(), ORG, 1, "ROLE_ADMIN")))
                    .isInstanceOf(ResponseStatusException.class);
            release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (service.status(job.id(), actor).state() == UsageExportService.State.RUNNING
                    && System.nanoTime() < end) Thread.sleep(10);
            assertThat(service.status(job.id(), actor).state()).isEqualTo(UsageExportService.State.READY);
            String csv = new String(service.download(job.id(), actor), StandardCharsets.UTF_8);
            assertThat(csv).contains(ORG.toString(), "9007199254740993", "0.000000000000000001");
            when(clock.instant()).thenReturn(NOW.plusSeconds(900));
            service.purge();
            assertThatThrownBy(() -> service.download(job.id(), actor))
                    .isInstanceOf(ResponseStatusException.class);
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void deniesForeignTenantAndOrdinaryUserBeforeQueue() {
        var service = new UsageExportService(Clock.fixed(NOW, ZoneOffset.UTC),
                mock(UsageQueryService.class), mock(UsagePhysicalSpendService.class));
        try {
            assertThatThrownBy(() -> service.start(request(UUID.randomUUID()), actor(USER, ORG, 1, "ROLE_ADMIN")))
                    .isInstanceOf(ForbiddenOperationException.class);
            assertThatThrownBy(() -> service.start(request(null), actor(USER, ORG, 1, "ROLE_USER")))
                    .isInstanceOf(ForbiddenOperationException.class);
        } finally {
            service.close();
        }
    }

    @Test
    void csvNeutralizesFormulaAndRejectsOversizedResults() {
        assertThat(UsageExportCsv.cell(" \t=1+1")).isEqualTo("\"' \t=1+1\"");
        assertThat(UsageExportCsv.cell("x;\"y")).isEqualTo("\"x;\"\"y\"");
        assertThatThrownBy(() -> UsageExportCsv.encode(request(null),
                java.util.Collections.nCopies(UsageExportCsv.MAX_ROWS + 1, "row"), NOW))
                .isInstanceOf(UsageExportCsv.LimitExceeded.class);
    }

    @Test
    void failedJobsNeverExposePartialCsvOrInternalErrors() throws InterruptedException {
        var query = mock(UsageQueryService.class);
        when(query.exportRows(any(), any(), any())).thenThrow(new UsageExportCsv.LimitExceeded())
                .thenThrow(new IllegalStateException("secret SQL diagnostic"));
        var service = new UsageExportService(Clock.fixed(NOW, ZoneOffset.UTC),
                query, mock(UsagePhysicalSpendService.class));
        try {
            var actor = actor(USER, ORG, 1, "ROLE_ADMIN");
            var request = new UsageExportRequest(UsageExportRequest.Kind.SUMMARY, NOW.minusSeconds(60),
                    NOW, null, null, null, null, null);
            for (String failure : List.of("LIMIT_EXCEEDED", "REPORT_UNAVAILABLE")) {
                var job = service.start(request, actor);
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (service.status(job.id(), actor).state() != UsageExportService.State.FAILED
                        && System.nanoTime() < end) Thread.sleep(10);
                assertThat(service.status(job.id(), actor).failure()).isEqualTo(failure);
                assertThatThrownBy(() -> service.download(job.id(), actor))
                        .isInstanceOf(ResponseStatusException.class);
            }
        } finally {
            service.close();
        }
    }

    private static UsageExportRequest request(UUID organization) {
        return new UsageExportRequest(UsageExportRequest.Kind.PROVIDERS, NOW.minusSeconds(60), NOW,
                organization, null, null, null, null);
    }

    private static SafeAiUserPrincipal actor(UUID id, UUID organization, long version, String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(id, organization, version, 1,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
